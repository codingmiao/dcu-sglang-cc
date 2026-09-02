package org.wowtools.dcu.pojo;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.wowtools.dcu.util.Constant;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证 #1：请求体各嵌套层级的未知字段经 readValue 收进 extras、
 * writeValueAsString 后全部仍在（不丢、不因新参数 500）。
 */
class AnthropicMessageRequestTest {

    private static final String JSON = """
            {
              "model": "claude-xxx",
              "max_tokens": 1024,
              "stream": true,
              "x_custom": 1,
              "system": [
                { "type": "text", "text": "You are helpful.", "x_sys": "a" }
              ],
              "messages": [
                { "role": "user", "content": "hi", "x_msg": 3 }
              ],
              "tools": [
                { "name": "Bash", "description": "run", "input_schema": {"type":"object"}, "x_tool": true }
              ],
              "metadata": { "user_id": "u1", "x_meta": 2 }
            }
            """;

    @Test
    void unknownFieldsRoundTrip() throws Exception {
        AnthropicMessageRequest req =
                Constant.objectMapper.readValue(JSON, AnthropicMessageRequest.class);

        // 各嵌套层未知字段都进了 extras
        assertEquals(1, ((Number) req.getExtras().get("x_custom")).intValue());
        // system 现为 Object（#7），数组元素反序列化为 Map，未知字段 x_sys 仍在其中
        assertEquals("a", ((List<java.util.Map<String, Object>>) req.getSystem()).get(0).get("x_sys"));
        assertEquals(3, ((Number) req.getMessages().get(0).getExtras().get("x_msg")).intValue());
        assertEquals(true, req.getTools().get(0).getExtras().get("x_tool"));
        assertEquals(2, ((Number) req.getMetadata().getExtras().get("x_meta")).intValue());

        // 序列化回写后未知字段全部仍在
        String out = Constant.objectMapper.writeValueAsString(req);
        JsonNode node = Constant.objectMapper.readTree(out);
        assertEquals(1, node.get("x_custom").asInt());
        assertEquals("a", node.get("system").get(0).get("x_sys").asText());
        assertEquals(3, node.get("messages").get(0).get("x_msg").asInt());
        assertEquals(true, node.get("tools").get(0).get("x_tool").asBoolean());
        assertEquals(2, node.get("metadata").get("x_meta").asInt());
    }

    /**
     * 验证 #7：system 为纯字符串时能解析并原样序列化回同样的字符串。
     */
    @Test
    void systemAsStringRoundTrip() throws Exception {
        String json = """
                {
                  "model": "claude-xxx",
                  "max_tokens": 64,
                  "system": "You are a helpful assistant.",
                  "messages": [ { "role": "user", "content": "hi" } ]
                }
                """;
        AnthropicMessageRequest req =
                Constant.objectMapper.readValue(json, AnthropicMessageRequest.class);
        assertEquals("You are a helpful assistant.", req.getSystem());

        JsonNode node = Constant.objectMapper.readTree(Constant.objectMapper.writeValueAsString(req));
        assertEquals("You are a helpful assistant.", node.get("system").asText());
    }
}
