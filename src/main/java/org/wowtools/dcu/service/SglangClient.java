package org.wowtools.dcu.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;
import org.wowtools.dcu.config.DcuConfiguration;
import org.wowtools.dcu.pojo.AnthropicMessageRequest;
import org.wowtools.dcu.pojo.AnthropicMessageResponse;
import org.wowtools.dcu.pojo.AnthropicStreamData;
import org.wowtools.dcu.util.Constant;

import java.io.BufferedReader;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * sglang 后端 HTTP 客户端。
 * 非流式返回完整响应体；流式逐事件回调。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SglangClient {

    private final DcuConfiguration config;

    private OkHttpClient okHttpClient;
    private String url;
    private String apiKey;
    private String innerModel;

    @PostConstruct
    public void init() {
        DcuConfiguration.Backend b = config.getBackend();
        url = b.getBaseUrl() + "/v1/messages";
        apiKey = b.getApiKey();
        innerModel = b.getInnerName();
        okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(config.getConnectTimeout(), TimeUnit.SECONDS)
                .readTimeout(config.getReadTimeout(), TimeUnit.SECONDS)
                .writeTimeout(config.getWriteTimeout(), TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.SECONDS)
                .build();
        log.info("sglang 客户端就绪: url={}, model={}", url, innerModel);
    }

    /**
     * 非流式请求。
     */
    public AnthropicMessageResponse send(AnthropicMessageRequest request, String logId) {
        request.setModel(innerModel);
        request.setStream(false);
        String strBody = toJson(request);
        Request httpRequest = buildRequest(strBody, false);

        try (Response response = okHttpClient.newCall(httpRequest).execute()) {
            String responseBody = response.body() != null ? response.body().string() : null;
            if (!response.isSuccessful()) {
                throw new UpstreamException(response.code(), responseBody);
            }
            return Constant.objectMapper.readValue(responseBody, AnthropicMessageResponse.class);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("sglang 非流式请求异常[" + logId + "]", e);
        }
    }

    /**
     * 流式请求，逐事件回调。
     *
     * @param onCall 上游 {@link okhttp3.Call} 创建后回调，调用方可持有引用以便在
     *              客户端断开时 {@code call.cancel()} 立即中断上游读取（#12）
     */
    public void sendStream(AnthropicMessageRequest request, String logId,
                           Consumer<AnthropicStreamData> onEvent, Consumer<okhttp3.Call> onCall) {
        request.setModel(innerModel);
        request.setStream(true);
        String strBody = toJson(request);
        Request httpRequest = buildRequest(strBody, true);

        okhttp3.Call call = okHttpClient.newCall(httpRequest);
        if (onCall != null) {
            onCall.accept(call);
        }
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                String err = response.body() != null ? response.body().string() : null;
                throw new UpstreamException(response.code(), err);
            }
            try (BufferedReader reader = new BufferedReader(response.body().charStream())) {
                String line;
                String currentEventType = null;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event: ")) {
                        currentEventType = line.substring(7).trim();
                    } else if (line.startsWith("data: ")) {
                        String dataStr = line.substring(6).trim();
                        if (dataStr.startsWith("[")) {
                            continue; // 心跳
                        }
                        if (currentEventType != null) {
                            AnthropicStreamData streamData =
                                    Constant.objectMapper.readValue(dataStr, AnthropicStreamData.class);
                            streamData.setType(currentEventType);
                            onEvent.accept(streamData);
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("sglang 流式请求异常[" + logId + "]", e);
        }
    }

    private Request buildRequest(String strBody, boolean stream) {
        RequestBody body = RequestBody.create(
                strBody,
                okhttp3.MediaType.get("application/json;charset=UTF-8"));
        Request.Builder builder = new Request.Builder()
                .url(url)
                .post(body);
        if (stream) {
            builder.addHeader("Accept", "text/event-stream");
        }
        if (apiKey != null) {
            builder.header("x-api-key", apiKey);
        }
        builder.header("anthropic-version", "2023-06-01");
        return builder.build();
    }

    private String toJson(AnthropicMessageRequest request) {
        try {
            return Constant.objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
