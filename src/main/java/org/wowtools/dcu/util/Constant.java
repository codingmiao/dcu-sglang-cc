package org.wowtools.dcu.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * 全局常量。
 */
public class Constant {
    public static final ObjectMapper objectMapper = new ObjectMapper();

    static {
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        // 禁用 FAIL_ON_EMPTY_BEANS，避免序列化空对象时报错
        objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        // 未知字段不抛异常（客户端带新参数时不 500）；保真由 @JsonAnySetter 的 extras 承接
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}
