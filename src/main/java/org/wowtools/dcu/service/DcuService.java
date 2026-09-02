package org.wowtools.dcu.service;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.wowtools.dcu.config.DcuConfiguration;
import org.wowtools.dcu.pojo.AnthropicMessageRequest;
import org.wowtools.dcu.pojo.AnthropicMessageResponse;
import org.wowtools.dcu.pojo.AnthropicStreamData;
import org.wowtools.dcu.stats.JsonlLogService;
import org.wowtools.dcu.stats.LinesChangedCalculator;
import org.wowtools.dcu.stats.RequestStat;
import org.wowtools.dcu.stats.ServiceMetrics;
import org.wowtools.dcu.stats.StatsStore;
import org.wowtools.dcu.util.Constant;
import org.wowtools.dcu.util.ServletUtil;

import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;

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
    public void handle(AnthropicMessageRequest request, String user, String logId, HttpServletResponse response) throws Exception {
        long start = System.currentTimeMillis();
        metrics.requestStarted();
        boolean stream = request.isStream();
        // 原始请求快照（#8）：SglangClient 会就地改 model/stream，记录侧用这份快照，
        // 保证 jsonl/统计里是客户端真正请求的 model，而非转发用的 innerModel
        JsonNode originalRequest = Constant.objectMapper.valueToTree(request);
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

            recordStat(logId, user, originalRequest, fullResponse, cost, stream, success, stopReason);
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            recordStat(logId, user, originalRequest, null, cost, stream, false, null);
            if (e instanceof ClientGoneException) {
                // 客户端主动断开（#12）：上游已 cancel，属正常情况，INFO 记录、不写错误响应
                log.info("id:{}\tuser:{}\tstream:{}\t客户端断开，请求中止", logId, user, stream);
                return;
            }
            // 后端异常 / 超时 / 解析失败：以 Anthropic 错误格式返回，而非 Spring 默认 500
            log.error("处理请求失败 id:{} user:{} stream:{}", logId, user, stream, e);
            if (response.isCommitted()) {
                // 已开始输出（流式中途失败）：无法再改状态码，error 事件已在 handleStream 内发出
                return;
            }
            UpstreamException upstream = unwrapUpstream(e);
            if (upstream != null) {
                // 上游错误：原样透传状态码与错误体（保留 429/5xx 可重试语义，见 #4）
                writeUpstreamError(response, upstream.getStatus(), upstream.getBody());
            } else {
                writeError(response, 500, "api_error", "internal server error: " + e.getMessage());
            }
        } finally {
            metrics.requestFinished();
        }
    }

    /**
     * 从异常链里找出上游错误（可能被 RuntimeException 包装）。
     */
    private UpstreamException unwrapUpstream(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof UpstreamException u) {
                return u;
            }
        }
        return null;
    }

    /**
     * 把上游错误原样透传给客户端：相同状态码 + 原始错误体（保留 429/5xx 可重试语义）。
     */
    private void writeUpstreamError(HttpServletResponse response, int status, String body) {
        try {
            response.setStatus(status);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(body == null ? "" : body);
            response.getWriter().flush();
        } catch (Exception ex) {
            log.error("透传上游错误响应失败", ex);
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
            // 修复 2：丢弃与块类型不匹配的 delta
            StreamFix streamFix = new StreamFix(config.getFix().isMismatchedDelta());
            // 收集完整响应（用于统计 usage / 记录 jsonl）；只收集放行的事件，保证记录与客户端所见一致
            StreamResponseCollector collector = new StreamResponseCollector();
            // 上游 Call 引用 + 客户端断开标记（#12）：客户端断开时 cancel 上游，立即中断读取
            java.util.concurrent.atomic.AtomicReference<okhttp3.Call> callRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicBoolean clientGone =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

            try {
                client.sendStream(request, logId, event -> {
                    // 客户端断开检测：PrintWriter 写失败不抛异常，只能轮询 checkError()。
                    // 一旦检测到，cancel 上游 Call 让读取立即中断，避免白读完整流。
                    if (writer.checkError()) {
                        okhttp3.Call c = callRef.get();
                        if (c != null) {
                            c.cancel();
                        }
                        clientGone.set(true);
                        throw new ClientGoneException();
                    }
                    try {
                        if (!streamFix.shouldForward(event)) {
                            return;
                        }
                        collector.accept(event);
                        sendEvent(writer, event);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, callRef::set);

                AnthropicMessageResponse full = collector.build();
                log.info("id:{}\tuser:{}\tstream:true\tmodel:{}\tdroppedMismatchedDelta:{}",
                        logId, user, request.getModel(), streamFix.getDroppedMismatchedDelta());
                holder.set(full);
            } catch (Exception e) {
                if (clientGone.get()) {
                    // 客户端已断开：上游已 cancel，无需再向客户端发 error 事件；
                    // 直接上抛，由 handle 的外层 catch 统一记录（#12）
                    throw e;
                }
                UpstreamException upstream = unwrapUpstream(e);
                if (!response.isCommitted() && upstream != null) {
                    // 上游在开始输出前就失败（如 404/429）：原样透传状态码 + 错误体（#4）。
                    // 必须在此处（writer 尚未关闭）写入，否则外层 catch 时响应已被提交。
                    writeUpstreamError(response, upstream.getStatus(), upstream.getBody());
                } else {
                    // 流式中途失败：向客户端发一个 error 事件，避免客户端干等
                    sendErrorEvent(writer, e.getMessage());
                }
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

    /**
     * 客户端断开（#12）：由流式事件回调在检测到 {@code writer.checkError()} 时抛出，
     * 用于区分"客户端走了"与"上游/内部错误"，前者无需再向客户端发 error 事件。
     */
    private static final class ClientGoneException extends RuntimeException {
        ClientGoneException() {
            super("client disconnected");
        }
    }

    private void recordStat(String logId, String user, JsonNode originalRequest,
                           AnthropicMessageResponse res, long cost, boolean stream,
                           boolean success, String stopReason) {
        int inTok = 0, outTok = 0;
        if (res != null && res.getUsage() != null) {
            inTok = res.getUsage().getInputTokens();
            outTok = res.getUsage().getOutputTokens();
        }
        // 改动行数：从响应的 Write/Edit tool_use 块算（见 LinesChangedCalculator）
        int linesChanged = LinesChangedCalculator.calculate(res);

        RequestStat stat = new RequestStat();
        stat.setLogId(logId);
        stat.setUser(user);
        // model 取自原始快照（客户端真正请求的模型名），而非被 SglangClient 改过的 innerModel
        stat.setModel(originalRequest.path("model").asText(null));
        stat.setStream(stream);
        stat.setInputTokens(inTok);
        stat.setOutputTokens(outTok);
        stat.setLinesChanged(linesChanged);
        stat.setCost(cost);
        stat.setStopReason(stopReason);
        stat.setSuccess(success);
        stat.setTs(System.currentTimeMillis());
        statsStore.insert(stat);

        jsonlLogService.record(logId, user, originalRequest, res, cost);
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
                    AnthropicMessageResponse.ContentBlock block = blocks.get(index);
                    if (builder == null || block == null || e.getDelta() == null) {
                        return;
                    }
                    // 按块类型拼接，避免混入的异类 delta 污染内容（如 tool_use 块里的 text_delta）
                    String deltaType = e.getDelta().getType();
                    switch (block.getType()) {
                        case "tool_use" -> {
                            if ("input_json_delta".equals(deltaType) && e.getDelta().getPartialJson() != null) {
                                builder.append(e.getDelta().getPartialJson());
                            }
                        }
                        case "text" -> {
                            if ("text_delta".equals(deltaType) && e.getDelta().getText() != null) {
                                builder.append(e.getDelta().getText());
                            }
                        }
                        case "thinking" -> {
                            if ("thinking_delta".equals(deltaType) && e.getDelta().getThinking() != null) {
                                builder.append(e.getDelta().getThinking());
                            }
                        }
                        default -> {
                        }
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
