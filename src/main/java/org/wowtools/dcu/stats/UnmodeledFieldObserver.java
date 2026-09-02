package org.wowtools.dcu.stats;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.wowtools.dcu.config.DcuConfiguration;
import org.wowtools.dcu.pojo.AnthropicMessageRequest;
import org.wowtools.dcu.util.Constant;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 未建模字段观测：记录"客户端在发、但代理未建模"的字段（经 #1 的 extras 原样转发、不丢数据），
 * 供人工分析协议漂移、决定是否把某字段升级为一等公民。
 *
 * <p>生产-消费模式（对齐 {@link JsonlLogService}）：Controller 在热路径仅入队，
 * 后台单消费线程 walk 请求的 extras、按字段路径集合去重累加、变更时刷写
 * {@code data/unmodeled-fields.jsonl}。不存完整请求体（jsonl 已有全量），
 * 只存 sampleLogId（关联可查）+ 截断样本。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnmodeledFieldObserver {

    private static final int QUEUE_CAPACITY = 10000;
    private static final int SAMPLE_MAX = 200;
    private static final String FILE_NAME = "unmodeled-fields.jsonl";

    private final DcuConfiguration config;
    private final UnmodeledFieldTracker tracker = new UnmodeledFieldTracker();

    private final BlockingQueue<RequestRef> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    private volatile boolean running = true;
    private Thread consumerThread;

    @PostConstruct
    public void init() {
        consumerThread = Thread.ofVirtual().name("unmodeled-field-observer").start(this::consume);
        log.info("未建模字段观测线程已启动, file={}/{}", config.getStats().getDataDir(), FILE_NAME);
    }

    /**
     * 业务接口：仅入队（热路径零阻塞）。
     */
    public void record(String logId, AnthropicMessageRequest request) {
        if (!running) {
            return;
        }
        if (!queue.offer(new RequestRef(logId, request))) {
            log.warn("未建模字段观测队列已满，丢弃: logId={}", logId);
        }
    }

    private void consume() {
        while (running || !queue.isEmpty()) {
            try {
                RequestRef ref = queue.poll(1, TimeUnit.SECONDS);
                if (ref == null) {
                    continue;
                }
                List<String> paths = new ArrayList<>();
                Map<String, Object> sampleMap = new LinkedHashMap<>();
                collect(ref.request, paths, sampleMap);
                if (paths.isEmpty()) {
                    continue;
                }
                String sample = truncate(toJson(sampleMap), SAMPLE_MAX);
                boolean changed = tracker.track(paths, System.currentTimeMillis(), ref.logId, sample);
                if (changed) {
                    flushFile();
                }
            } catch (InterruptedException e) {
                log.info("未建模字段观测线程收到中断信号");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("未建模字段观测处理异常", e);
            }
        }
    }

    /**
     * walk 请求各嵌套层级的 extras，收集未建模字段路径与样本。
     * 路径前缀：顶层直接记名，Tool→tool.，Metadata→metadata.，ConversationMessage→message.。
     * （system 现为 Object 原样透传，整体即未建模，无内部字段可观测。）
     */
    private void collect(AnthropicMessageRequest req, List<String> paths, Map<String, Object> sampleMap) {
        addExtras(req.getExtras(), "", paths, sampleMap);
        // 注：system 现为 Object 原样透传（#7），整体即"未建模"，无内部字段可观测，故不在此收集
        if (req.getTools() != null) {
            for (AnthropicMessageRequest.Tool t : req.getTools()) {
                addExtras(t.getExtras(), "tool.", paths, sampleMap);
            }
        }
        if (req.getMetadata() != null) {
            addExtras(req.getMetadata().getExtras(), "metadata.", paths, sampleMap);
        }
        if (req.getMessages() != null) {
            for (AnthropicMessageRequest.ConversationMessage cm : req.getMessages()) {
                addExtras(cm.getExtras(), "message.", paths, sampleMap);
            }
        }
    }

    private void addExtras(Map<String, Object> extras, String prefix,
                           List<String> paths, Map<String, Object> sampleMap) {
        if (extras == null) {
            return;
        }
        for (Map.Entry<String, Object> e : extras.entrySet()) {
            String path = prefix + e.getKey();
            paths.add(path);
            sampleMap.put(path, e.getValue());
        }
    }

    /** 变更时刷写：整文件重写（记录数小，一行一条）。 */
    private void flushFile() {
        try {
            Path dir = Paths.get(config.getStats().getDataDir());
            Files.createDirectories(dir);
            List<String> lines = new ArrayList<>();
            for (UnmodeledFieldTracker.Record r : tracker.snapshot()) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("paths", r.paths);
                line.put("count", r.count);
                line.put("firstSeen", r.firstSeen);
                line.put("lastSeen", r.lastSeen);
                line.put("sampleLogId", r.sampleLogId);
                line.put("sample", r.sample);
                lines.add(Constant.objectMapper.writeValueAsString(line));
            }
            Files.write(dir.resolve(FILE_NAME), lines, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("写未建模字段观测文件失败", e);
        }
    }

    private String toJson(Object o) {
        try {
            return Constant.objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    @PreDestroy
    public void destroy() {
        running = false;
        if (consumerThread != null) {
            try {
                consumerThread.join(10000);
            } catch (InterruptedException e) {
                log.error("等待未建模字段观测线程退出超时");
            }
        }
    }

    private static final class RequestRef {
        final String logId;
        final AnthropicMessageRequest request;

        RequestRef(String logId, AnthropicMessageRequest request) {
            this.logId = logId;
            this.request = request;
        }
    }
}
