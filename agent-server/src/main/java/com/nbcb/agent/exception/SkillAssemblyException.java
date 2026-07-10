package com.nbcb.agent.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Skill 组装失败异常 — 代码模板组装过程中出现异常。
 * <p>
 * HTTP 500：表示组装逻辑出错（非 LLM 问题，是代码 bug 或数据格式问题）。
 *
 * @author com.nbcb
 */
@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
public class SkillAssemblyException extends RuntimeException {

    public SkillAssemblyException(String message) {
        super(message);
    }

    public SkillAssemblyException(String message, Throwable cause) {
        super(message, cause);
    }
}
