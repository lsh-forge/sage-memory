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

/** Allow anonymous access; validate X-Api-Key before Agent work whenever the header is supplied. */
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
        // Health remains public even when an invalid key is supplied.
        return "GET".equals(request.getMethod()) && "/health".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Enumeration<String> values = request.getHeaders(HEADER_NAME);
        if (values == null || !values.hasMoreElements()) {
            chain.doFilter(request, response);
            return;
        }
        String supplied = values.nextElement();
        boolean duplicate = values.hasMoreElements();
        if (supplied == null || duplicate || !MessageDigest.isEqual(expectedKey, supplied.getBytes(StandardCharsets.UTF_8))) {
            // Never echo the supplied header, configured key, request body or query string.
            log.warn("[接口鉴权拒绝] 已传入的X-Api-Key无效或重复；HTTP状态=401，不进入Add/Search处理");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"error_code\":\"UNAUTHORIZED\",\"message\":\"Invalid or duplicate X-Api-Key.\"}");
            return;
        }
        // OncePerRequestFilter skips async redispatch by default, preserving DeferredResult responses.
        chain.doFilter(request, response);
    }
}
