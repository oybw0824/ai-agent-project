package com.nbcb.agent.exception;

import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.nbcb.agent.util.ResponseBuilder;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/**
 * ★ 全局异常处理器 — 统一错误响应格式
 * <p>
 * 拦截所有 Controller 层异常，返回一致的 JSON 错误结构，
 * 避免将堆栈信息泄露到前端。
 *
 * @author com.nbcb
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 参数校验失败（@Valid 校验 → MethodArgumentNotValidException）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        log.warn("参数校验失败: {}", message);
        return ResponseBuilder.error(400, message);
    }

    /**
     * 请求体不可读（JSON 格式错误等）
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleNotReadable(HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败: {}", ex.getMessage());
        return ResponseBuilder.error(400, "请求体格式错误，请检查 JSON 格式");
    }

    /**
     * 类型转换失败（如路径参数类型不匹配）
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("参数类型不匹配: {}", ex.getMessage());
        return ResponseBuilder.error(400, "参数类型错误: " + ex.getName());
    }

    /**
     * 约束违反（如 @NotBlank 等直接校验）
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleConstraintViolation(ConstraintViolationException ex) {
        log.warn("约束违反: {}", ex.getMessage());
        return ResponseBuilder.error(400, "参数校验失败");
    }

    /**
     * 非法参数（业务校验抛出的 IllegalArgumentException）
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("非法参数: {}", ex.getMessage());
        return ResponseBuilder.error(400, "请求参数不合法");
    }

    /**
     * 超时异常处理（java.util.concurrent.TimeoutException）
     */
    @ExceptionHandler(java.util.concurrent.TimeoutException.class)
    @ResponseStatus(HttpStatus.GATEWAY_TIMEOUT)
    public Map<String, Object> handleTimeout(java.util.concurrent.TimeoutException ex) {
        log.error("请求超时: {}", ex.getMessage());
        return ResponseBuilder.error(504, "请求超时，请稍后重试");
    }

    /**
     * ★ v2: LLM 调用失败（超时、连接失败、空响应）
     */
    @ExceptionHandler(LlmCallException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, Object> handleLlmCall(LlmCallException ex) {
        log.error("LLM 调用失败: {}", ex.getMessage(), ex);
        return ResponseBuilder.error(503, "AI 服务暂时不可用，请稍后重试");
    }

    /**
     * ★ v2: LLM 返回 JSON 不可解析（重试多次后仍失败）
     */
    @ExceptionHandler(LlmJsonInvalidException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Map<String, Object> handleLlmJsonInvalid(LlmJsonInvalidException ex) {
        log.error("LLM JSON 解析失败: {}", ex.getMessage());
        if (ex.getRawResponse() != null) {
            log.debug("LLM 原始响应: {}", ex.getRawResponse());
        }
        return ResponseBuilder.error(502, "AI 返回内容格式异常，请稍后重试");
    }

    /**
     * ★ v2: Skill 组装失败（代码模板异常）
     */
    @ExceptionHandler(SkillAssemblyException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> handleSkillAssembly(SkillAssemblyException ex) {
        log.error("Skill 组装失败: {}", ex.getMessage(), ex);
        return ResponseBuilder.error(500, "技能生成内部错误，请稍后重试");
    }

    /**
     * ★ 阶段间结构化校验失败 — 某阶段 LLM 输出 JSON 缺失关键字段
     */
    @ExceptionHandler(StageValidationException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String, Object> handleStageValidation(StageValidationException ex) {
        log.error("阶段校验失败 [{}]: 缺失字段={}", ex.getStageName(), ex.getMissingFields());
        return ResponseBuilder.error(422, ex.getStageName() + " 输出结构不完整: 缺失 "
                + String.join(", ", ex.getMissingFields()));
    }

    /**
     * ★ v2: 重试耗尽（Spring Retry 达到最大次数）
     */
    @ExceptionHandler(ExhaustedRetryException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, Object> handleExhaustedRetry(ExhaustedRetryException ex) {
        log.error("重试耗尽: {}", ex.getMessage(), ex);
        return ResponseBuilder.error(503, "服务暂时不可用，已达到最大重试次数");
    }

    /**
     * ★ GraphRunnerException — Agent 执行异常（框架层异常）
     */
    @ExceptionHandler(GraphRunnerException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> handleGraphRunner(GraphRunnerException ex) {
        log.error("Agent 执行异常", ex);
        return ResponseBuilder.error(500, "Agent 处理请求时发生内部错误，请稍后重试");
    }

    /**
     * ★ Agent 早期终止异常 — 治理 Hook 触发限流/超时/空转检测
     */
    @ExceptionHandler(AgentEarlyTerminationException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String, Object> handleAgentEarlyTermination(AgentEarlyTerminationException ex) {
        log.warn("Agent 早期终止 [reason={}, sessionId={}]: {}",
                ex.getReason(), ex.getSessionId(), ex.getMessage());
        return ResponseBuilder.error(422,
                "Agent 会话已终止 [" + ex.getReason() + "]");
    }

    /**
     * 兜底运行时异常 — 不暴露内部信息到前端
     */
    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> handleRuntime(RuntimeException ex) {
        log.error("运行时异常", ex);
        return ResponseBuilder.error(500, "服务器内部错误");
    }

    /**
     * 兜底异常处理
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> handleUnknown(Exception ex) {
        log.error("未预期的异常", ex);
        return ResponseBuilder.error(500, "服务器内部错误");
    }
}