package com.nbcb.agent.skill.dynamic;

import org.springframework.http.HttpStatus;

/**
 * 动态 Skill 运行时错误码。
 */
public enum DynamicSkillErrorCode {

    AGENT_NOT_FOUND(HttpStatus.NOT_FOUND),
    AGENT_SKILL_NOT_BOUND(HttpStatus.NOT_FOUND),
    SKILL_PARSE_FAILED(HttpStatus.UNPROCESSABLE_ENTITY),
    SKILL_BINDING_QUERY_FAILED(HttpStatus.SERVICE_UNAVAILABLE),
    SKILL_FILE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    SKILL_SNAPSHOT_NOT_READY(HttpStatus.SERVICE_UNAVAILABLE);

    private final HttpStatus httpStatus;

    DynamicSkillErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
