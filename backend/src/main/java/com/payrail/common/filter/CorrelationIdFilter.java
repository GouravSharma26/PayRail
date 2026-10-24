package com.payrail.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Filter that establishes and propagates the correlation ID for request tracing across logs and HTTP headers.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationIdHeader = request.getHeader(CORRELATION_ID_HEADER);
        String correlationId;

        if (correlationIdHeader != null && !correlationIdHeader.isBlank()) {
            try {
                // Validate that provided header is a valid UUID
                UUID parsed = UUID.fromString(correlationIdHeader.trim());
                correlationId = parsed.toString();
            } catch (IllegalArgumentException e) {
                // If invalid UUID supplied, generate a fresh one
                correlationId = UUID.randomUUID().toString();
            }
        } else {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        request.setAttribute(CORRELATION_ID_HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * Resolves the current correlation ID from MDC or generates one.
     */
    public static String getCurrentCorrelationId() {
        String cid = MDC.get(MDC_KEY);
        if (cid == null || cid.isBlank()) {
            cid = UUID.randomUUID().toString();
            MDC.put(MDC_KEY, cid);
        }
        return cid;
    }
}
