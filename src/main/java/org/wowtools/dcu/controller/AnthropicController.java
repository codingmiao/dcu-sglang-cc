package org.wowtools.dcu.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.wowtools.dcu.pojo.AnthropicMessageRequest;
import org.wowtools.dcu.service.ConcurrencyGate;
import org.wowtools.dcu.service.DcuService;
import org.wowtools.dcu.service.UserRegistry;
import org.wowtools.dcu.util.Constant;

/**
 * Anthropic /v1/messages 代理入口。
 * 鉴权：请求头 x-api-key 必须是已配置的可用用户 apiKey。
 * 并发控制：超过 max-concurrency 排队，排队满或等待超时返回 503 系统繁忙。
 */
@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class AnthropicController {

    private final DcuService dcuService;
    private final UserRegistry userRegistry;
    private final ConcurrencyGate gate;

    @PostMapping(value = "/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public void messages(@RequestBody String body,
                         HttpServletRequest request,
                         HttpServletResponse response) throws Exception {
//        String apiKey = request.getHeader("x-api-key");
        String apiKey = request.getHeader("authorization");
        if (null != apiKey) {
            apiKey = apiKey.replaceFirst("Bearer ","").trim();
        }
        if (!userRegistry.isValid(apiKey)) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                    "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid api key\"}}");
            return;
        }
        String user = userRegistry.nameOf(apiKey);

        // 并发门：拿不到在途许可（排队满 / 等待超时）即系统繁忙
        if (!gate.acquire()) {
            response.setStatus(503);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                    "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"system busy, please retry later\"}}");
            return;
        }
        try {
            AnthropicMessageRequest anthropicMessageRequest =
                    Constant.objectMapper.readValue(body, AnthropicMessageRequest.class);
            dcuService.handle(anthropicMessageRequest, user, response);
        } finally {
            gate.release();
        }
    }
}
