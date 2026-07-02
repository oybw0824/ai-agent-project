package com.nbcb.agent.exception;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.LinkedHashMap;
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
        return errorBody(400, message);
    }

    /**
     * 请求体不可读（JSON 格式错误等）
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleNotReadable(HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败: {}", ex.getMessage());
        return errorBody(400, "请求体格式错误，请检查 JSON 格式");
    }

    /**
     * 类型转换失败（如路径参数类型不匹配）
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("参数类型不匹配: {}", ex.getMessage());
        return errorBody(400, "参数类型错误: " + ex.getName());
    }

    /**
     * 约束违反（如 @NotBlank 等直接校验）
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleConstraintViolation(ConstraintViolationException ex) {
        log.warn("约束违反: {}", ex.getMessage());
        return errorBody(400, "参数校验失败: " + ex.getMessage());
    }

    /**
     * 非法参数（业务校验抛出的 IllegalArgumentException）
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("非法参数: {}", ex.getMessage());
        return errorBody(400, ex.getMessage());
    }

    /**
     * Agent 执行异常（AgentService 在 GraphRunnerException 等异常时抛出）
     * <p>
     * ★ 区别于通用 RuntimeException：仅当异常信息包含 "Agent" 关键字时按 Agent 异常处理，
     * 避免将其他运行时异常（NPE、IllegalState 等）误标记为 "Agent 错误"。
     */
    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> handleRuntime(RuntimeException ex) {
        String msg = ex.getMessage();
        // ★ 精确判断：只有明确的 Agent 异常才显示友好提示，其他异常直接报错
        if (msg != null && (msg.contains("Agent") || msg.contains("Graph") || msg.contains("graph"))) {
            log.error("Agent 执行异常", ex);
            return errorBody(500, "Agent 处理请求时发生内部错误，请稍后重试");
        }
        log.error("运行时异常", ex);
        return errorBody(500, "服务器内部错误: " + (msg != null ? msg : ex.getClass().getSimpleName()));
    }

    /**
     * 兜底异常处理
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> handleUnknown(Exception ex) {
        log.error("未预期的异常", ex);
        return errorBody(500, "服务器内部错误");
    }

    // ==================== 私有辅助 ====================

    private Map<String, Object> errorBody(int code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", code);
        body.put("error", message);
        return body;
    }
}