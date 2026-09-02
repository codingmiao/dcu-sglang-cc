package org.wowtools.dcu.service;

/**
 * 上游（sglang）返回非 2xx 时抛出，携带原始 status 与 body。
 * 供代理把上游错误原样透传给客户端（保留状态码与错误体语义），
 * 而不是统一吞成 500（见 code-review #4）。
 */
public class UpstreamException extends RuntimeException {

    private final int status;
    private final String body;

    public UpstreamException(int status, String body) {
        super("upstream HTTP " + status);
        this.status = status;
        this.body = body;
    }

    public int getStatus() {
        return status;
    }

    public String getBody() {
        return body;
    }
}
