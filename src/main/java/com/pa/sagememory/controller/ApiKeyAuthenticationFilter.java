package com.pa.sagememory.controller;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Enumeration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticate before reading request bodies or starting any Agent work. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public final class ApiKeyAuthenticationFilter extends OncePerRequestFilter {
    public static final String HEADER_NAME = "X-Api-Key";
    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);
    private final byte[] expectedKey;

    public ApiKeyAuthenticationFilter(ApiKeyProperties properties) {
        properties.validate();
        expectedKey = properties.getApiKey().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        // Only the public GET health endpoint is exempt. Default protection also prevents path-alias bypasses.
        return "GET".equals(request.getMethod()) && "/health".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Enumeration<String> values = request.getHeaders(HEADER_NAME);
        String supplied = values != null && values.hasMoreElements() ? values.nextElement() : null;
        boolean duplicate = values != null && values.hasMoreElements();
        if (supplied == null || duplicate || !MessageDigest.isEqual(expectedKey, supplied.getBytes(StandardCharsets.UTF_8))) {
            // Never echo the supplied header, configured key, request body or query string.
            log.warn("[接口鉴权拒绝] 缺少或无效的X-Api-Key；HTTP状态=401，不进入Add/Search处理");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"error_code\":\"UNAUTHORIZED\",\"message\":\"Missing or invalid X-Api-Key.\"}");
            return;
        }
        // OncePerRequestFilter skips async redispatch by default, preserving DeferredResult responses.
        chain.doFilter(request, response);
    }
}
