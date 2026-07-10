package com.nbcb.agent.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * LLM 返回的 JSON 无法解析异常 — 重试多次后仍无法提取有效 JSON。
 * <p>
 * HTTP 502：表示上游 LLM 返回了不可解析的内容，通常是模型输出格式问题。
 *
 * @author com.nbcb
 */
@ResponseStatus(HttpStatus.BAD_GATEWAY)
public class LlmJsonInvalidException extends RuntimeException {

    /** LLM 最后一次返回的原始文本（供调试） */
    private final String rawResponse;

    public LlmJsonInvalidException(String message) {
        this(message, null);
    }

    public LlmJsonInvalidException(String message, String rawResponse) {
        super(message);
        this.rawResponse = rawResponse;
    }

    public LlmJsonInvalidException(String message, Throwable cause, String rawResponse) {
        super(message, cause);
        this.rawResponse = rawResponse;
    }

    public String getRawResponse() {
        return rawResponse;
    }
}
