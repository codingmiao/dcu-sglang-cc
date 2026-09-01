package org.wowtools.dcu.pojo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Anthropic 流式（SSE）响应事件实体。
 * 对应 message_start / content_block_start / content_block_delta /
 * content_block_stop / message_delta / message_stop 等事件。
 */
@Data
public class AnthropicStreamData {

    /** 事件类型（由解析器从 "event: xxx" 行填充） */
    @JsonProperty("type")
    private String type;

    @JsonProperty("message")
    private MessageStartData message;

    @JsonProperty("index")
    private Integer index;

    @JsonProperty("content_block")
    private ContentBlockStartData contentBlock;

    @JsonProperty("delta")
    private ContentBlockDeltaData delta;

    @JsonProperty("usage")
    private UsageData usage;

    @JsonProperty("error")
    private Object error;

    @Data
    public static class MessageStartData {
        @JsonProperty("id")
        private String id;

        @JsonProperty("type")
        private String type;

        @JsonProperty("role")
        private String role;

        @JsonProperty("model")
        private String model;

        @JsonProperty("content")
        private Object[] content;

        @JsonProperty("stop_reason")
        private Object stopReason;

        @JsonProperty("stop_sequence")
        private Object stopSequence;

        @JsonProperty("usage")
        private Usage usage;
    }

    @Data
    public static class ContentBlockStartData {
        @JsonProperty("type")
        private String type;

        @JsonProperty("text")
        private String text;

        @JsonProperty("id")
        private String id;

        @JsonProperty("name")
        private String name;

        @JsonProperty("input")
        private Object input;

        @JsonProperty("thinking")
        private String thinking;

        @JsonProperty("signature")
        private String signature;

        @JsonProperty("tool_use_id")
        private String toolUseId;

        @JsonProperty("content")
        private Object content;
    }

    @Data
    public static class ContentBlockDeltaData {
        @JsonProperty("type")
        private String type;

        @JsonProperty("text")
        private String text;

        @JsonProperty("partial_json")
        private String partialJson;

        @JsonProperty("stop_sequence")
        private String stopSequence;

        @JsonProperty("stop_reason")
        private String stopReason;

        @JsonProperty("thinking")
        private String thinking;

        @JsonProperty("signature")
        private String signature;
    }

    @Data
    public static class UsageData {
        @JsonProperty("input_tokens")
        private Integer inputTokens;

        @JsonProperty("output_tokens")
        private Integer outputTokens;

        @JsonProperty("cache_read_input_tokens")
        private Integer cacheReadInputTokens;

        @JsonProperty("cache_creation_input_tokens")
        private Integer cacheCreationInputTokens;
    }

    @Data
    public static class Usage {
        @JsonProperty("input_tokens")
        private Integer inputTokens;

        @JsonProperty("output_tokens")
        private Integer outputTokens;
    }
}
