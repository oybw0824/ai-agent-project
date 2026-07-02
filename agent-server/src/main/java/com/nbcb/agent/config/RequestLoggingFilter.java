package com.nbcb.agent.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;

/**
 * 请求日志过滤器 — 记录每个 HTTP 请求的方法、URI、状态码和耗时
 * <p>
 * 本地调试时快速定位请求处理情况，生产环境可用于监控和排查。
 * <p>
 * ★ 优化：大响应体（>16KB）跳过 ContentCachingResponseWrapper 包装，
 * 避免将大响应全部缓冲到内存导致 OOM，仅记录响应大小。
 *
 * @author com.nbcb
 */
@Slf4j
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    /** 响应体缓冲上限（超过此大小不缓存，仅记录字节数） */
    private static final int CACHE_LIMIT_BYTES = 16 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // ★ SSE 流式请求跳过此过滤器，避免 ContentCachingResponseWrapper 缓冲破坏实时推送
        String uri = request.getRequestURI();
        return uri.startsWith("/chat/stream") || uri.startsWith("/skill/generate-stream");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        String method = request.getMethod();
        String uri = request.getRequestURI();
        String query = request.getQueryString();

        // ★ 大响应体不加缓冲包装，避免 OOM
        boolean useWrapper = !uri.startsWith("/chat") || "GET".equals(method);
        ContentCachingResponseWrapper responseWrapper = useWrapper
                ? new ContentCachingResponseWrapper(response)
                : null;

        try {
            if (useWrapper) {
                filterChain.doFilter(request, responseWrapper);
            } else {
                filterChain.doFilter(request, response);
            }
        } finally {
            long elapsed = System.currentTimeMillis() - start;
            int status = useWrapper ? responseWrapper.getStatus() : response.getStatus();
            int contentLength = useWrapper ? responseWrapper.getContentSize() : -1;

            log.info("{} {} {} {}ms {}bytes",
                    method,
                    query != null ? uri + "?" + query : uri,
                    status,
                    elapsed,
                    contentLength >= 0 ? contentLength : "?");

            if (useWrapper) {
                responseWrapper.copyBodyToResponse();
            }
        }
    }
}