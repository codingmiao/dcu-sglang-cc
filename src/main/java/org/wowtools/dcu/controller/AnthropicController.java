package org.wowtools.dcu.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.wowtools.dcu.pojo.AnthropicMessageRequest;
import org.wowtools.dcu.service.ConcurrencyGate;
import org.wowtools.dcu.service.DcuService;
import org.wowtools.dcu.service.UserConcurrencyGate;
import org.wowtools.dcu.service.UserRegistry;
import org.wowtools.dcu.stats.UnmodeledFieldObserver;
import org.wowtools.dcu.util.Constant;

import java.util.UUID;

/**
 * Anthropic /v1/messages 代理入口。
 * 鉴权：请求头 x-api-key 必须是已配置的可用用户 apiKey。
 * 并发控制（两级）：
 * <ul>
 *   <li>用户级：单用户超过其 max_concurrency 立即拒绝，返回 429（不排队）。</li>
 *   <li>全局级：超过 max-concurrency 排队，排队满或等待超时返回 503 系统繁忙。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class AnthropicController {

    private final DcuService dcuService;
    private final UserRegistry userRegistry;
    private final ConcurrencyGate gate;
    private final UserConcurrencyGate userGate;
    private final UnmodeledFieldObserver unmodeledFieldObserver;

    @PostMapping("/messages")
    public void messages(@RequestBody String body,
                         HttpServletRequest request,
                         HttpServletResponse response) throws Exception {
        // 两种鉴权头都支持：x-api-key（Anthropic 官方，裸 key）优先，其次 Authorization: Bearer
        String apiKey = request.getHeader("x-api-key");
        if (apiKey == null) {
            String auth = request.getHeader("authorization");
            if (auth != null) {
                apiKey = auth.replaceFirst("Bearer ", "").trim();
            }
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

        // 用户级并发门：单用户超过其 max_concurrency 立即拒绝（429，不排队）
        int userLimit = userRegistry.maxConcurrencyOf(apiKey);
        if (!userGate.acquire(user, userLimit)) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                    "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"user concurrency limit reached, please retry later\"}}");
            return;
        }
        // 全局并发门：拿不到在途许可（排队满 / 等待超时）即系统繁忙
        if (!gate.acquire()) {
            userGate.release(user);
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
            // logId 在此生成并贯穿：观测的 sampleLogId 与 jsonl/统计记录用同一个，可关联回查
            String logId = UUID.randomUUID().toString();
            unmodeledFieldObserver.record(logId, anthropicMessageRequest);
            dcuService.handle(anthropicMessageRequest, user, logId, response);
        } finally {
            gate.release();
            userGate.release(user);
        }
    }
}
