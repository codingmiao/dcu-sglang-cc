package org.wowtools.dcu.stats;

import org.wowtools.dcu.pojo.AnthropicMessageResponse;

import java.util.Map;

/**
 * 从一次响应的 tool_use 块里算"AI 改动了多少行文件"。
 *
 * <p>口径（只统计真正写文件的两个工具，Bash/Read 等不计）：
 * <ul>
 *   <li><b>Write</b>：整文件写入，取 {@code input.content} 的行数。</li>
 *   <li><b>Edit</b>：局部替换，取 {@code input.new_string} 的行数（AI 实际写进文件的内容）。</li>
 * </ul>
 * 一次响应里可能有多个 Write/Edit 块，行数累加。
 *
 * <p>行数按 {@code \n} 切分、末尾换行不产生额外空行（与编辑器/IDE 的行数一致）；
 * 空串记 0 行。
 */
public final class LinesChangedCalculator {

    private LinesChangedCalculator() {
    }

    /**
     * 统计一次响应里 Write/Edit 工具改动的总行数。
     *
     * @param res 完整响应（流式由 collector 还原、非流式直接解析）；可为 null
     * @return 改动行数；无 Write/Edit 块或响应为空时返回 0
     */
    public static int calculate(AnthropicMessageResponse res) {
        if (res == null || res.getContent() == null) {
            return 0;
        }
        int total = 0;
        for (AnthropicMessageResponse.ContentBlock block : res.getContent()) {
            if (block == null || !"tool_use".equals(block.getType())) {
                continue;
            }
            Map<String, Object> input = block.getInput();
            if (input == null) {
                continue;
            }
            String name = block.getName();
            if ("Write".equals(name)) {
                total += lineCount(input.get("content"));
            } else if ("Edit".equals(name)) {
                total += lineCount(input.get("new_string"));
            }
        }
        return total;
    }

    /**
     * 取字符串的行数（按 {@code \n} 切、末尾换行不产生额外空行）；非字符串或空串返回 0。
     */
    private static int lineCount(Object value) {
        if (!(value instanceof String s) || s.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                lines++;
            }
        }
        // 末尾换行不产生额外空行
        if (s.charAt(s.length() - 1) == '\n') {
            lines--;
        }
        return lines;
    }
}
