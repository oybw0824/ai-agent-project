package com.nbcb.agent.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.List;

/**
 * 阶段间结构化校验异常 — 某阶段产出的 JSON 不满足下游所需的最小结构要求。
 * <p>
 * HTTP 422：表示上游 LLM 输出可解析但结构不完整（缺关键字段），属于数据质量问题，
 * 非服务端内部错误。尽早抛出可避免坏数据流入下游阶段浪费 LLM token。
 *
 * @author com.nbcb
 */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class StageValidationException extends RuntimeException {

    /** 出错的阶段名（如 "阶段一[拆解]"） */
    private final String stageName;

    /** 缺失或不合规的字段列表 */
    private final List<String> missingFields;

    public StageValidationException(String stageName, List<String> missingFields) {
        super(stageName + " 输出结构校验失败，缺失/不合规字段: " + missingFields);
        this.stageName = stageName;
        this.missingFields = missingFields;
    }

    public StageValidationException(String stageName, List<String> missingFields, String rawJson) {
        super(stageName + " 输出结构校验失败，缺失/不合规字段: " + missingFields);
        this.stageName = stageName;
        this.missingFields = missingFields;
    }

    public String getStageName() {
        return stageName;
    }

    public List<String> getMissingFields() {
        return missingFields;
    }
}
