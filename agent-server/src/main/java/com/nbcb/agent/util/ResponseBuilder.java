package com.nbcb.agent.util;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 响应构建器 — 统一构建 API 响应对象
 * <p>
 * 提供标准的响应格式，消除重复代码。
 * 支持成功响应、错误响应、元数据响应等多种场景。
 *
 * @author com.nbcb
 */
public final class ResponseBuilder {

    private ResponseBuilder() {
        // 工具类，禁止实例化
    }

    /**
     * 构建错误响应
     *
     * @param statusCode HTTP 状态码
     * @param message    错误消息
     * @return 错误响应 Map
     */
    public static Map<String, Object> error(int statusCode, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", statusCode);
        body.put("error", message);
        return body;
    }

    /**
     * 构建错误响应（带请求路径）
     *
     * @param statusCode HTTP 状态码
     * @param message    错误消息
     * @param path       请求路径
     * @return 错误响应 Map
     */
    public static Map<String, Object> error(int statusCode, String message, String path) {
        Map<String, Object> body = error(statusCode, message);
        body.put("path", path);
        return body;
    }

    /**
     * 构建错误响应（带异常类型）
     *
     * @param statusCode HTTP 状态码
     * @param message    错误消息
     * @param exception  异常类型
     * @return 错误响应 Map
     */
    public static Map<String, Object> error(int statusCode, String message, String exception, String path) {
        Map<String, Object> body = error(statusCode, message, path);
        body.put("exception", exception);
        return body;
    }

    /**
     * 构建成功响应
     *
     * @param data 响应数据
     * @return 成功响应 Map
     */
    public static Map<String, Object> success(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", 200);
        body.put("data", data);
        return body;
    }

    /**
     * 构建成功响应（带消息）
     *
     * @param message 响应消息
     * @param data    响应数据
     * @return 成功响应 Map
     */
    public static Map<String, Object> success(String message, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", 200);
        body.put("message", message);
        body.put("data", data);
        return body;
    }

    /**
     * 构建分页响应
     *
     * @param data       数据列表
     * @param total      总数
     * @param page       当前页码
     * @param pageSize   每页大小
     * @return 分页响应 Map
     */
    public static Map<String, Object> paginated(List<?> data, long total, int page, int pageSize) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", 200);
        body.put("data", data);
        body.put("pagination", Map.of(
                "total", total,
                "page", page,
                "pageSize", pageSize,
                "totalPages", (total + pageSize - 1) / pageSize
        ));
        return body;
    }

    /**
     * 构建元数据响应（用于 SSE done 事件等）
     *
     * @param metadata 元数据
     * @return 元数据响应 Map
     */
    public static Map<String, Object> metadata(Map<String, Object> metadata) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("metadata", metadata);
        return body;
    }
}