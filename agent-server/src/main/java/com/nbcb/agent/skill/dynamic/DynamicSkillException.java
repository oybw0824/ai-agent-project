package com.nbcb.agent.skill.dynamic;

/**
 * 动态 Skill 加载异常。
 */
public class DynamicSkillException extends RuntimeException {

    private final DynamicSkillErrorCode errorCode;

    public DynamicSkillException(DynamicSkillErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public DynamicSkillException(DynamicSkillErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public DynamicSkillErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * 从框架包装异常链中查找动态 Skill 根因。
     */
    public static DynamicSkillException findCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof DynamicSkillException dynamicSkillException) {
                return dynamicSkillException;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return null;
    }
}
