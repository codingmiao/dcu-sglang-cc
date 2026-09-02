package org.wowtools.dcu.stats;

import org.junit.jupiter.api.Test;
import org.wowtools.dcu.pojo.AnthropicMessageResponse;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证改动行数口径：Write 取 content 行数、Edit 取 new_string 行数，
 * 多个块累加，非 Write/Edit 工具（Bash/Read）不计。
 */
class LinesChangedCalculatorTest {

    private AnthropicMessageResponse.ContentBlock toolUse(String name, Map<String, Object> input) {
        AnthropicMessageResponse.ContentBlock b = new AnthropicMessageResponse.ContentBlock();
        b.setType("tool_use");
        b.setName(name);
        b.setInput(input);
        return b;
    }

    private AnthropicMessageResponse response(AnthropicMessageResponse.ContentBlock... blocks) {
        AnthropicMessageResponse res = new AnthropicMessageResponse();
        res.setContent(List.of(blocks));
        return res;
    }

    @Test
    void nullOrEmptyResponseIsZero() {
        assertEquals(0, LinesChangedCalculator.calculate(null));
        assertEquals(0, LinesChangedCalculator.calculate(new AnthropicMessageResponse()));
    }

    @Test
    void writeCountsContentLines() {
        // 3 行内容（末尾无换行）
        AnthropicMessageResponse res = response(
                toolUse("Write", Map.of("file_path", "/a.txt", "content", "a\nb\nc")));
        assertEquals(3, LinesChangedCalculator.calculate(res));
    }

    @Test
    void editCountsNewStringLines() {
        // old_string 4 行、new_string 5 行：只算 new_string
        AnthropicMessageResponse res = response(
                toolUse("Edit", Map.of(
                        "file_path", "/a.txt",
                        "old_string", "1\n2\n3\n4",
                        "new_string", "1\n2\n3\n4\n5",
                        "replace_all", false)));
        assertEquals(5, LinesChangedCalculator.calculate(res));
    }

    @Test
    void multipleBlocksAccumulate() {
        AnthropicMessageResponse res = response(
                toolUse("Write", Map.of("file_path", "/a.txt", "content", "x\ny")),
                toolUse("Edit", Map.of(
                        "file_path", "/b.txt",
                        "old_string", "old",
                        "new_string", "n1\nn2\nn3",
                        "replace_all", false)));
        // Write 2 行 + Edit 3 行 = 5
        assertEquals(5, LinesChangedCalculator.calculate(res));
    }

    @Test
    void nonFileToolsAreIgnored() {
        AnthropicMessageResponse res = response(
                toolUse("Bash", Map.of("command", "ls")),
                toolUse("Read", Map.of("file_path", "/a.txt")),
                toolUse("TaskStop", Map.of()));
        assertEquals(0, LinesChangedCalculator.calculate(res));
    }

    @Test
    void textBlocksAreIgnored() {
        AnthropicMessageResponse.ContentBlock text = new AnthropicMessageResponse.ContentBlock();
        text.setType("text");
        text.setText("hello\nworld");
        AnthropicMessageResponse res = response(text);
        assertEquals(0, LinesChangedCalculator.calculate(res));
    }

    @Test
    void missingOrNonStringInputIsZero() {
        // Edit 缺 new_string
        AnthropicMessageResponse res = response(
                toolUse("Edit", Map.of("file_path", "/a.txt", "old_string", "x")));
        assertEquals(0, LinesChangedCalculator.calculate(res));
    }

    @Test
    void trailingNewlineDoesNotAddEmptyLine() {
        // "a\nb\n" 是 2 行（末尾换行不产生第 3 个空行）
        AnthropicMessageResponse res = response(
                toolUse("Write", Map.of("file_path", "/a.txt", "content", "a\nb\n")));
        assertEquals(2, LinesChangedCalculator.calculate(res));
    }
}
