package org.wowtools.dcu.stats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.wowtools.dcu.config.DcuConfiguration;
import org.wowtools.dcu.util.Constant;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按 logId 从 jsonl 明细文件里 grep 出完整 request/response。
 *
 * <p>只扫未压缩的 {@code request_*.jsonl}（新→旧）；不扫 .gz（避免解压开销）。
 * 找不到即视为"已压缩/已清理"，由前端提示。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JsonlLookupService {

    private final DcuConfiguration config;
    private final ObjectMapper objectMapper = Constant.objectMapper;

    /**
     * 查找结果。
     */
    public record LookupResult(boolean found, Map<String, Object> entry, String reason) {
        static LookupResult notFound(String reason) {
            return new LookupResult(false, null, reason);
        }
    }

    public LookupResult find(String logId) {
        if (logId == null || logId.isBlank()) {
            return LookupResult.notFound("invalid");
        }
        Path dir = Paths.get(config.getStats().getDataDir());
        if (!Files.isDirectory(dir)) {
            return LookupResult.notFound("missing");
        }
        // 收集未压缩 jsonl，按文件名倒序（新文件在前）
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "request_*.jsonl")) {
            for (Path p : ds) {
                files.add(p);
            }
        } catch (Exception e) {
            log.warn("列举 jsonl 目录失败: {}", dir, e);
            return LookupResult.notFound("missing");
        }
        files.sort(Comparator.comparing(Path::getFileName).reversed());

        for (Path file : files) {
            Map<String, Object> entry = scanFile(file, logId);
            if (entry != null) {
                return new LookupResult(true, entry, null);
            }
        }
        return LookupResult.notFound("compressed");
    }

    /**
     * 逐行扫描单个 jsonl，命中 logId 返回该条；否则 null。
     */
    private Map<String, Object> scanFile(Path file, String logId) {
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.contains(logId)) {
                    continue;
                }
                try {
                    JsonNode node = objectMapper.readTree(line);
                    if (logId.equals(node.path("logId").asText())) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("timestamp", node.path("timestamp").asText());
                        m.put("logId", node.path("logId").asText());
                        m.put("user", node.path("user").asText());
                        m.put("cost", node.path("cost").asLong());
                        m.put("request", node.get("request"));
                        m.put("response", node.get("response"));
                        m.put("error", node.get("error"));
                        m.put("sourceFile", file.getFileName().toString());
                        return m;
                    }
                } catch (Exception parseEx) {
                    // 单行解析失败跳过
                }
            }
        } catch (Exception e) {
            log.warn("扫描 jsonl 失败: {}", file, e);
        }
        return null;
    }
}
