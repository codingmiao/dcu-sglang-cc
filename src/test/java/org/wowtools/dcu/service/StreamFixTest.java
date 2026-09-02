package org.wowtools.dcu.service;

import org.junit.jupiter.api.Test;
import org.wowtools.dcu.pojo.AnthropicStreamData;
import org.wowtools.dcu.util.Constant;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用 sglang 真实返回的流（text(0) + tool_use(1) + tool_use(2)，
 * 且 index 1 中混入一条 text_delta "\n"）回放验证过滤器。
 */
class StreamFixTest {

    /** 抓包得到的原始 data 行 */
    private static final String[] RAW_DATA_LINES = {
            "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_x\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"chat_min\",\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}}",
            "{\"type\":\"content_block_start\",\"content_block\":{\"type\":\"text\",\"text\":\"\"},\"index\":0}",
            "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"\\n\\n\"},\"index\":0}",
            "{\"type\":\"content_block_stop\",\"index\":0}",
            "{\"type\":\"content_block_start\",\"content_block\":{\"type\":\"tool_use\",\"id\":\"call_1\",\"name\":\"Bash\",\"input\":{}},\"index\":1}",
            "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\"},\"index\":1}",
            "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"command\\\": \\\"pwd\\\"\"},\"index\":1}",
            // sglang 的毛病：tool_use 块里混入 text_delta，客户端会报
            // "Content block is not a text block"
            "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"\\n\"},\"index\":1}",
            "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"}\"},\"index\":1}",
            "{\"type\":\"content_block_stop\",\"index\":1}",
            "{\"type\":\"content_block_start\",\"content_block\":{\"type\":\"tool_use\",\"id\":\"call_2\",\"name\":\"Bash\",\"input\":{}},\"index\":2}",
            "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\"},\"index\":2}",
            "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"command\\\": \\\"ls -la\\\"\"},\"index\":2}",
            "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"}\"},\"index\":2}",
            "{\"type\":\"content_block_stop\",\"index\":2}",
            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"input_tokens\":370,\"output_tokens\":87}}",
            "{\"type\":\"message_stop\"}",
    };

    private static List<AnthropicStreamData> parse() throws Exception {
        List<AnthropicStreamData> events = new ArrayList<>();
        for (String line : RAW_DATA_LINES) {
            events.add(Constant.objectMapper.readValue(line, AnthropicStreamData.class));
        }
        return events;
    }

    @Test
    void fixOn() throws Exception {
        StreamFix fix = new StreamFix(true);
        List<AnthropicStreamData> forwarded = new ArrayList<>();
        for (AnthropicStreamData event : parse()) {
            if (fix.shouldForward(event)) {
                forwarded.add(event);
            }
        }
        // 只丢弃 1 条混入 tool_use 块的 text_delta，其余（含两个 tool_use）全部放行
        assertEquals(1, fix.getDroppedMismatchedDelta());
        assertEquals(RAW_DATA_LINES.length - 1, forwarded.size());
        for (AnthropicStreamData event : forwarded) {
            assertFalse("content_block_delta".equals(event.getType())
                            && event.getDelta() != null
                            && "text_delta".equals(event.getDelta().getType())
                            && event.getIndex() != null && event.getIndex() == 1,
                    "tool_use 块里混入的 text_delta 应被丢弃");
        }
    }

    @Test
    void fixOffForwardsEverything() throws Exception {
        StreamFix fix = new StreamFix(false);
        int forwarded = 0;
        for (AnthropicStreamData event : parse()) {
            if (fix.shouldForward(event)) {
                forwarded++;
            }
        }
        assertEquals(RAW_DATA_LINES.length, forwarded);
        assertEquals(0, fix.getDroppedMismatchedDelta());
    }

    @Test
    void legalDeltasUnaffected() throws Exception {
        // 合法流（纯文本回复）不应丢任何事件
        String[] legal = {
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_x\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"chat_min\",\"usage\":{}}}",
                "{\"type\":\"content_block_start\",\"content_block\":{\"type\":\"text\",\"text\":\"\"},\"index\":0}",
                "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"},\"index\":0}",
                "{\"type\":\"content_block_stop\",\"index\":0}",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1}}",
                "{\"type\":\"message_stop\"}",
        };
        StreamFix fix = new StreamFix(true);
        int forwarded = 0;
        for (String line : legal) {
            if (fix.shouldForward(Constant.objectMapper.readValue(line, AnthropicStreamData.class))) {
                forwarded++;
            }
        }
        assertEquals(legal.length, forwarded);
        assertTrue(fix.getDroppedMismatchedDelta() == 0);
    }
}
