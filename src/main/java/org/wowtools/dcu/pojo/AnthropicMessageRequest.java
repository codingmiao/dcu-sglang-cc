package org.wowtools.dcu.pojo;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic API 请求体（/v1/messages）。
 * 字段尽量宽松：未识别字段经 {@code @JsonAnySetter} 收进 extras、序列化时原样回写，
 * 保证能原样转发给 sglang（不丢字段、不因新参数 500）。
 */
@Data
public class AnthropicMessageRequest {

    @JsonProperty("model")
    private String model;

    // 协议允许 system 为纯字符串或对象数组，用 Object 承接原样透传（见 code-review #7）
    @JsonProperty("system")
    private Object system;

    @JsonProperty("messages")
    private List<ConversationMessage> messages;

    @JsonProperty("max_tokens")
    private int maxTokens;

    @JsonProperty("temperature")
    private Double temperature;

    @JsonProperty("stream")
    private boolean stream;

    @JsonProperty("tools")
    private List<Tool> tools;

    @JsonProperty("tool_choice")
    private Object toolChoice;

    @JsonProperty("metadata")
    private Metadata metadata;

    @JsonProperty("thinking")
    private Object thinking;

    @JsonProperty("output_config")
    private Object outputConfig;

    @JsonProperty("context_management")
    private Object contextManagement;

    @JsonProperty("stop_sequences")
    private List<String> stopSequences;

    @JsonProperty("top_p")
    private Double topP;

    @JsonProperty("top_k")
    private Integer topK;

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private final Map<String, Object> extras = new LinkedHashMap<>();

    @JsonAnySetter
    public void setExtra(String key, Object value) {
        extras.put(key, value);
    }

    @JsonAnyGetter
    public Map<String, Object> getExtras() {
        return extras;
    }

    @Data
    public static class Metadata {
        @JsonProperty("user_id")
        private String userId;

        @Getter(AccessLevel.NONE)
        @Setter(AccessLevel.NONE)
        private final Map<String, Object> extras = new LinkedHashMap<>();

        @JsonAnySetter
        public void setExtra(String key, Object value) {
            extras.put(key, value);
        }

        @JsonAnyGetter
        public Map<String, Object> getExtras() {
            return extras;
        }
    }

    /**
     * 会话消息。content 可能是 String 或 List<ContentBlock>，用 Object 承接。
     */
    @Data
    public static class ConversationMessage {
        @JsonProperty("role")
        private String role;

        @JsonProperty("content")
        private Object content;

        @Getter(AccessLevel.NONE)
        @Setter(AccessLevel.NONE)
        private final Map<String, Object> extras = new LinkedHashMap<>();

        @JsonAnySetter
        public void setExtra(String key, Object value) {
            extras.put(key, value);
        }

        @JsonAnyGetter
        public Map<String, Object> getExtras() {
            return extras;
        }
    }

    @Data
    public static class Tool {
        @JsonProperty("name")
        private String name;

        @JsonProperty("description")
        private String description;

        @JsonProperty("input_schema")
        private Map<String, Object> inputSchema;

        @JsonProperty("type")
        private Object type;

        @Getter(AccessLevel.NONE)
        @Setter(AccessLevel.NONE)
        private final Map<String, Object> extras = new LinkedHashMap<>();

        @JsonAnySetter
        public void setExtra(String key, Object value) {
            extras.put(key, value);
        }

        @JsonAnyGetter
        public Map<String, Object> getExtras() {
            return extras;
        }
    }
}
