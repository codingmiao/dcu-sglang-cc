package org.wowtools.dcu.pojo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Anthropic API 非流式响应体（/v1/messages）。
 */
@Data
public class AnthropicMessageResponse {

    @JsonProperty("id")
    private String id;

    @JsonProperty("type")
    private String type;

    @JsonProperty("role")
    private String role;

    @JsonProperty("content")
    private List<ContentBlock> content;

    @JsonProperty("model")
    private String model;

    @JsonProperty("stop_reason")
    private String stopReason;

    @JsonProperty("stop_sequence")
    private Object stopSequence;

    @JsonProperty("usage")
    private Usage usage;

    @Data
    public static class ContentBlock {
        @JsonProperty("type")
        private String type;

        @JsonProperty("text")
        private String text;

        @JsonProperty("id")
        private String id;

        @JsonProperty("name")
        private String name;

        @JsonProperty("input")
        private Map<String, Object> input;

        @JsonProperty("thinking")
        private String thinking;

        @JsonProperty("signature")
        private String signature;
    }

    @Data
    public static class Usage {
        @JsonProperty("input_tokens")
        private int inputTokens;

        @JsonProperty("output_tokens")
        private int outputTokens;

        @JsonProperty("cache_read_input_tokens")
        private Object cacheReadInputTokens;

        @JsonProperty("cache_creation_input_tokens")
        private Object cacheCreationInputTokens;
    }
}
