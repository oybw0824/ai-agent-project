package com.nbcb.agent.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * LLM 调用失败异常 — 超时、连接失败、空响应等。
 * <p>
 * HTTP 503：表示 LLM 服务暂不可用，客户端可稍后重试。
 *
 * @author com.nbcb
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class LlmCallException extends RuntimeException {

    public LlmCallException(String message) {
        super(message);
    }

    public LlmCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
