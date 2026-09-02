package org.wowtools.dcu.stats;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 未建模字段观测的纯逻辑：按"排序后的字段路径集合"去重，同键累加 count、更新 lastSeen。
 * 无 I/O、无线程，便于单测。由 {@link UnmodeledFieldObserver} 在消费线程里调用。
 */
public class UnmodeledFieldTracker {

    /** 内存去重键上限：超限后新键忽略，防异常客户端撑爆内存。 */
    public static final int MAX_KEYS = 1000;

    private final Map<String, Record> records = new LinkedHashMap<>();

    /**
     * 记录一次观测。
     *
     * @param paths  本次请求出现的未建模字段路径（可为空）
     * @param now    当前时间（epoch ms）
     * @param logId  关联 logId（首次出现时作为 sampleLogId，可回查 jsonl 全量）
     * @param sample 截断样本（≤200 字符）
     * @return 是否产生了变更（新增或更新）；空路径或超限新键返回 false
     */
    public boolean track(List<String> paths, long now, String logId, String sample) {
        if (paths == null || paths.isEmpty()) {
            return false;
        }
        List<String> sorted = new ArrayList<>(paths);
        Collections.sort(sorted);
        String key = String.join("|", sorted);
        Record rec = records.get(key);
        if (rec == null) {
            if (records.size() >= MAX_KEYS) {
                return false; // 超限，新键忽略
            }
            records.put(key, new Record(key, sorted, now, now, 1, logId, sample));
            return true;
        }
        rec.count++;
        rec.lastSeen = now;
        return true;
    }

    public Collection<Record> snapshot() {
        return records.values();
    }

    public int size() {
        return records.size();
    }

    /** 输出记录（一行一条）。字段名即 jsonl 键。 */
    public static class Record {
        public String key;
        public List<String> paths;
        public long firstSeen;
        public long lastSeen;
        public int count;
        public String sampleLogId;
        public String sample;

        public Record(String key, List<String> paths, long firstSeen, long lastSeen,
                      int count, String sampleLogId, String sample) {
            this.key = key;
            this.paths = paths;
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
            this.count = count;
            this.sampleLogId = sampleLogId;
            this.sample = sample;
        }
    }
}
