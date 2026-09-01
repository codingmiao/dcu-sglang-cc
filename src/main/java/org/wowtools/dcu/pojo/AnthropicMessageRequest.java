package org.wowtools.dcu.pojo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Anthropic API 请求体（/v1/messages）。
 * 字段尽量宽松，未识别字段忽略，保证能原样转发给 sglang。
 */
@Data
public class AnthropicMessageRequest {

    @JsonProperty("model")
    private String model;

    @JsonProperty("system")
    private List<SystemMessage> system;

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

    @Data
    public static class Metadata {
        @JsonProperty("user_id")
        private String userId;
    }

    @Data
    public static class SystemMessage {
        @JsonProperty("text")
        private String text;

        @JsonProperty("content")
        private String content;

        @JsonProperty("type")
        private String type;

        @JsonProperty("cache_control")
        private Object cacheControl;
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
    }
}
