package com.nbcb.agent.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 重试耗尽异常 — Spring Retry 达到最大重试次数后仍失败。
 * <p>
 * HTTP 503：表示服务暂不可用，客户端可稍后重试。
 *
 * @author com.nbcb
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class ExhaustedRetryException extends RuntimeException {

    public ExhaustedRetryException(String message) {
        super(message);
    }

    public ExhaustedRetryException(String message, Throwable cause) {
        super(message, cause);
    }
}
