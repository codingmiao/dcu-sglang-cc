package org.wowtools.dcu.service;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.wowtools.dcu.config.DcuConfiguration;
import org.wowtools.dcu.pojo.AnthropicMessageRequest;
import org.wowtools.dcu.pojo.AnthropicMessageResponse;
import org.wowtools.dcu.pojo.AnthropicStreamData;
import org.wowtools.dcu.stats.JsonlLogService;
import org.wowtools.dcu.stats.RequestStat;
import org.wowtools.dcu.stats.ServiceMetrics;
import org.wowtools.dcu.stats.StatsStore;
import org.wowtools.dcu.util.Constant;
import org.wowtools.dcu.util.ServletUtil;

import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 核心编排：鉴权后的请求 -> 修复 -> 转发 sglang -> 修复响应 -> 记录统计。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DcuService {

    private final DcuConfiguration config;
    private final SglangClient client;
    private final StatsStore statsStore;
    private final JsonlLogService jsonlLogService;
    private final ServiceMetrics metrics;

    /**
     * 处理一次 /v1/messages 请求（流式或非流式）。
     *
     * @param user 已通过鉴权反查出的用户名
     */
    public void handle(AnthropicMessageRequest request, String user, HttpServletResponse response) throws Exception {
        String logId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();
        metrics.requestStarted();
        boolean stream = request.isStream();
        try {
            // 修复 1：system role -> user
            if (config.getFix().isSystemRole()) {
                RequestFix.fixSystemRole(request);
            }

            AnthropicMessageResponse fullResponse = null;
            boolean success = false;
            String stopReason = null;

            if (stream) {
                fullResponse = handleStream(request, user, logId, response);
            } else {
                fullResponse = handleNoStream(request, user, logId, response);
            }

            long cost = System.currentTimeMillis() - start;
            if (fullResponse != null) {
                success = true;
                stopReason = fullResponse.getStopReason();
            }

            recordStat(logId, user, request, fullResponse, cost, stream, success, stopReason);
        } catch (Exception e) {
            // 后端异常 / 超时 / 解析失败：以 Anthropic 错误格式返回，而非 Spring 默认 500
            long cost = System.currentTimeMillis() - start;
            log.error("处理请求失败 id:{} user:{} stream:{}", logId, user, stream, e);
            recordStat(logId, user, request, null, cost, stream, false, null);
            if (stream) {
                // 流式：若尚未开始输出，返回 500 错误体；已开始则无法再改状态码
                if (!response.isCommitted()) {
                    writeError(response, 500, "api_error", "internal server error: " + e.getMessage());
                }
            } else {
                writeError(response, 500, "api_error", "internal server error: " + e.getMessage());
            }
        } finally {
            metrics.requestFinished();
        }
    }

    /**
     * 以 Anthropic 错误格式写响应体。
     */
    private void writeError(HttpServletResponse response, int status, String type, String message) {
        try {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("type", "error");
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("type", type);
            e.put("message", message == null ? "internal server error" : message);
            err.put("error", e);
            response.setStatus(status);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(Constant.objectMapper.writeValueAsString(err));
            response.getWriter().flush();
        } catch (Exception ex) {
            log.error("写错误响应失败", ex);
        }
    }

    private AnthropicMessageResponse handleNoStream(AnthropicMessageRequest request, String user,
                                                    String logId, HttpServletResponse response) throws Exception {
        AnthropicMessageResponse res = client.send(request, logId);

        // 修复 2：只保留第一个 tool_use
        if (config.getFix().isSingleToolUse()) {
            ResponseFix.fixSingleToolUse(res);
        }

        ServletUtil.text(Constant.objectMapper.writeValueAsString(res), 200, response);
        log.info("id:{}\tuser:{}\tstream:false\tmodel:{}\tcost:{}",
                logId, user, request.getModel(), System.currentTimeMillis());
        return res;
    }

    private AnthropicMessageResponse handleStream(AnthropicMessageRequest request, String user,
                                                  String logId, HttpServletResponse response) throws Exception {
        // 流式回调在 lambda 内同步执行，用局部 holder 回传完整响应（线程安全）
        java.util.concurrent.atomic.AtomicReference<AnthropicMessageResponse> holder =
                new java.util.concurrent.atomic.AtomicReference<>();

        ServletUtil.stream(response, writer -> {
            StreamToolUseFilter filter = new StreamToolUseFilter();
            // 收集完整响应（用于统计 usage / 记录 jsonl）
            StreamResponseCollector collector = new StreamResponseCollector();

            try {
                client.sendStream(request, logId, event -> {
                    try {
                        collector.accept(event);
                        // 修复 2：流式只放行第一个 tool_use
                        if (config.getFix().isSingleToolUse() && !filter.shouldForward(event)) {
                            return;
                        }
                        sendEvent(writer, event);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                // 流结束后，把收集到的完整响应也应用一次非流式修复，保证 jsonl 记录一致
                AnthropicMessageResponse full = collector.build();
                if (config.getFix().isSingleToolUse()) {
                    ResponseFix.fixSingleToolUse(full);
                }
                log.info("id:{}\tuser:{}\tstream:true\tmodel:{}\tdroppedToolUse:{}",
                        logId, user, request.getModel(), filter.getDroppedToolUse());
                holder.set(full);
            } catch (Exception e) {
                // 流式中途失败：向客户端发一个 error 事件，避免客户端干等
                sendErrorEvent(writer, e.getMessage());
                throw e;
            }
        });
        return holder.get();
    }

    private void sendEvent(PrintWriter writer, AnthropicStreamData event) throws Exception {
        String jsonData = Constant.objectMapper.writeValueAsString(event);
        writer.write("event: " + event.getType() + "\n");
        writer.write("data: " + jsonData + "\n\n");
        writer.flush();
    }

    /**
     * 流式中途失败时，向客户端发一个 Anthropic 风格的 error 事件。
     */
    private void sendErrorEvent(PrintWriter writer, String message) {
        try {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("type", "error");
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("type", "api_error");
            e.put("message", message == null ? "stream interrupted" : message);
            err.put("error", e);
            writer.write("event: error\n");
            writer.write("data: " + Constant.objectMapper.writeValueAsString(err) + "\n\n");
            writer.flush();
        } catch (Exception ex) {
            log.error("发送流式 error 事件失败", ex);
        }
    }

    private void recordStat(String logId, String user, AnthropicMessageRequest request,
                           AnthropicMessageResponse res, long cost, boolean stream,
                           boolean success, String stopReason) {
        int inTok = 0, outTok = 0;
        if (res != null && res.getUsage() != null) {
            inTok = res.getUsage().getInputTokens();
            outTok = res.getUsage().getOutputTokens();
        }

        RequestStat stat = new RequestStat();
        stat.setLogId(logId);
        stat.setUser(user);
        stat.setModel(request.getModel());
        stat.setStream(stream);
        stat.setInputTokens(inTok);
        stat.setOutputTokens(outTok);
        stat.setCost(cost);
        stat.setStopReason(stopReason);
        stat.setSuccess(success);
        stat.setTs(System.currentTimeMillis());
        statsStore.insert(stat);

        jsonlLogService.record(logId, user, request, res, cost);
    }

    /**
     * 流式事件收集器：把 SSE 事件还原成完整响应体（用于统计与 jsonl 记录）。
     */
    private static final class StreamResponseCollector {
        private String id;
        private String role;
        private String model;
        private String stopReason;
        private int inputTokens;
        private int outputTokens;
        private final java.util.Map<Integer, AnthropicMessageResponse.ContentBlock> blocks = new java.util.LinkedHashMap<>();
        private final java.util.Map<Integer, StringBuilder> builders = new java.util.HashMap<>();

        void accept(AnthropicStreamData e) {
            switch (e.getType()) {
                case "message_start" -> {
                    if (e.getMessage() != null) {
                        id = e.getMessage().getId();
                        role = e.getMessage().getRole();
                        model = e.getMessage().getModel();
                    }
                }
                case "content_block_start" -> {
                    int index = e.getIndex();
                    AnthropicStreamData.ContentBlockStartData cb = e.getContentBlock();
                    if (cb == null) {
                        return;
                    }
                    AnthropicMessageResponse.ContentBlock block = new AnthropicMessageResponse.ContentBlock();
                    block.setType(cb.getType());
                    block.setId(cb.getId());
                    block.setName(cb.getName());
                    blocks.put(index, block);
                    builders.put(index, new StringBuilder());
                }
                case "content_block_delta" -> {
                    int index = e.getIndex();
                    StringBuilder builder = builders.get(index);
                    if (builder == null || e.getDelta() == null) {
                        return;
                    }
                    if (e.getDelta().getText() != null) {
                        builder.append(e.getDelta().getText());
                    } else if (e.getDelta().getPartialJson() != null) {
                        builder.append(e.getDelta().getPartialJson());
                    } else if (e.getDelta().getThinking() != null) {
                        builder.append(e.getDelta().getThinking());
                    }
                }
                case "message_delta" -> {
                    if (e.getDelta() != null && e.getDelta().getStopReason() != null) {
                        stopReason = e.getDelta().getStopReason();
                    }
                    if (e.getUsage() != null) {
                        inputTokens = e.getUsage().getInputTokens() == null ? 0 : e.getUsage().getInputTokens();
                        outputTokens = e.getUsage().getOutputTokens() == null ? 0 : e.getUsage().getOutputTokens();
                    }
                }
                default -> {
                }
            }
        }

        AnthropicMessageResponse build() {
            AnthropicMessageResponse res = new AnthropicMessageResponse();
            res.setId(id);
            res.setType("message");
            res.setRole(role);
            res.setModel(model);
            res.setStopReason(stopReason);
            java.util.List<AnthropicMessageResponse.ContentBlock> content = new java.util.ArrayList<>();
            for (var entry : blocks.entrySet()) {
                AnthropicMessageResponse.ContentBlock block = entry.getValue();
                StringBuilder builder = builders.get(entry.getKey());
                String s = builder == null ? "" : builder.toString();
                if ("text".equals(block.getType())) {
                    block.setText(s);
                } else if ("thinking".equals(block.getType())) {
                    block.setThinking(s);
                } else if ("tool_use".equals(block.getType())) {
                    try {
                        block.setInput(s.isEmpty() ? new java.util.HashMap<>()
                                : Constant.objectMapper.readValue(s, java.util.Map.class));
                    } catch (Exception ex) {
                        log.warn("解析 tool_use input 失败: {}", s);
                    }
                }
                content.add(block);
            }
            res.setContent(content);
            AnthropicMessageResponse.Usage usage = new AnthropicMessageResponse.Usage();
            usage.setInputTokens(inputTokens);
            usage.setOutputTokens(outputTokens);
            res.setUsage(usage);
            return res;
        }
    }
}
