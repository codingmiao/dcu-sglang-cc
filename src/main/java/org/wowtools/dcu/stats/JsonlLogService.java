package org.wowtools.dcu.stats;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.wowtools.dcu.config.DcuConfiguration;
import org.wowtools.dcu.pojo.AnthropicMessageRequest;
import org.wowtools.dcu.pojo.AnthropicMessageResponse;
import org.wowtools.dcu.util.Constant;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * 全量请求/响应体日志（jsonl）。
 *
 * <p>对齐 lbrad 的 TrainingDataService：生产-消费模式，业务线程入队，
 * 后台单线程异步批量写 jsonl（消除锁竞争）；超过大小阈值滚动新文件，
 * 滚动时把旧文件 gzip 压缩（.jsonl -> .jsonl.gz），压缩成功后删原文件。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JsonlLogService {

    private static final int BATCH_SIZE = 100;
    private static final long FLUSH_INTERVAL_MS = 5000;
    private static final DateTimeFormatter FILE_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final DcuConfiguration config;
    private final ServiceMetrics metrics;
    private final ObjectMapper objectMapper = Constant.objectMapper;

    private final BlockingQueue<LogEntry> entryQueue = new LinkedBlockingQueue<>();

    private volatile boolean running = true;
    private Thread consumerThread;

    // 以下变量仅由 consumerThread 访问
    private String currentFileName;
    private long currentFileSize = 0;

    @PostConstruct
    public void init() {
        consumerThread = Thread.ofVirtual().name("jsonl-log-worker").start(this::consumeEntries);
        log.info("JsonlLogService 异步消费线程已启动, dataDir={}", config.getStats().getDataDir());
    }

    /**
     * 业务接口：仅入队。
     */
    public void record(String logId, String user, AnthropicMessageRequest request,
                       AnthropicMessageResponse response, long cost) {
        if (!running) {
            return;
        }
        LogEntry entry = new LogEntry(
                LocalDateTime.now().toString(),
                logId,
                user,
                request,
                response,
                cost
        );
        boolean success = entryQueue.offer(entry);
        metrics.setQueueDepth(entryQueue.size());
        if (!success) {
            log.warn("jsonl 日志队列已满，丢弃当前记录: logId={}", logId);
        }
    }

    private void consumeEntries() {
        List<LogEntry> batch = new ArrayList<>(BATCH_SIZE);
        long lastFlushTime = System.currentTimeMillis();

        while (running || !entryQueue.isEmpty()) {
            try {
                LogEntry entry = entryQueue.poll(1, TimeUnit.SECONDS);
                if (entry != null) {
                    batch.add(entry);
                }
                long now = System.currentTimeMillis();
                boolean shouldFlush = batch.size() >= BATCH_SIZE
                        || (now - lastFlushTime >= FLUSH_INTERVAL_MS && !batch.isEmpty())
                        || (!running && !batch.isEmpty());
                if (shouldFlush) {
                    writeBatch(batch);
                    batch.clear();
                    lastFlushTime = now;
                    metrics.setQueueDepth(entryQueue.size());
                }
            } catch (InterruptedException e) {
                log.info("jsonl 消费线程收到中断信号");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("jsonl 消费线程处理异常", e);
            }
        }
    }

    private void writeBatch(List<LogEntry> entries) {
        try {
            ensureDataDirectoryExists();

            StringBuilder sb = new StringBuilder();
            for (LogEntry entry : entries) {
                sb.append(objectMapper.writeValueAsString(entry)).append("\n");
            }
            byte[] contentBytes = sb.toString().getBytes(StandardCharsets.UTF_8);

            checkAndRotateFile(contentBytes.length);

            Files.write(Paths.get(currentFileName), contentBytes,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            currentFileSize += contentBytes.length;
        } catch (Exception e) {
            log.error("批量写入 jsonl 日志失败", e);
        }
    }

    private void checkAndRotateFile(long nextWriteSize) throws Exception {
        if (currentFileName == null || !Files.exists(Paths.get(currentFileName))
                || (currentFileSize + nextWriteSize > config.getStats().getMaxFileSize())) {
            if (currentFileName != null && Files.exists(Paths.get(currentFileName))) {
                compressFile(currentFileName);
            }
            String timestamp = LocalDateTime.now().format(FILE_DATE_FORMATTER);
            currentFileName = config.getStats().getDataDir() + "/request_" + timestamp + ".jsonl";
            currentFileSize = 0;
            log.info("切换新 jsonl 日志文件: {}", currentFileName);
        }
    }

    private void ensureDataDirectoryExists() throws Exception {
        Path dir = Paths.get(config.getStats().getDataDir());
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
    }

    private void compressFile(String sourcePath) {
        File sourceFile = new File(sourcePath);
        File targetFile = new File(sourcePath + ".gz");
        try (FileInputStream fis = new FileInputStream(sourceFile);
             FileOutputStream fos = new FileOutputStream(targetFile);
             GZIPOutputStream gzipOS = new GZIPOutputStream(fos);
             BufferedInputStream bis = new BufferedInputStream(fis)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = bis.read(buffer)) != -1) {
                gzipOS.write(buffer, 0, len);
            }
            log.info("jsonl 文件压缩完成: {} -> {}", sourcePath, targetFile.getName());
        } catch (Exception e) {
            log.error("压缩 jsonl 文件失败: {}", sourcePath, e);
            return;
        }
        try {
            Files.delete(Paths.get(sourcePath));
        } catch (Exception e) {
            log.warn("无法删除原始 jsonl 文件: {}", sourcePath);
        }
    }

    @PreDestroy
    public void destroy() {
        running = false;
        if (consumerThread != null) {
            try {
                consumerThread.join(10000);
            } catch (InterruptedException e) {
                log.error("等待 jsonl 消费线程退出超时");
            }
        }
    }

    public record LogEntry(
            String timestamp,
            String logId,
            String user,
            AnthropicMessageRequest request,
            AnthropicMessageResponse response,
            Long cost
    ) {
    }
}
