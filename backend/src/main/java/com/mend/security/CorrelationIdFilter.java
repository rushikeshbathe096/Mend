package com.mend.security;

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

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String TRACE_ID_HEADER = "X-Trace-ID";
    public static final String MDC_CORRELATION_ID_KEY = "correlationId";
    public static final String MDC_TRACE_ID_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = request.getHeader(TRACE_ID_HEADER);
        }
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_CORRELATION_ID_KEY, correlationId);
        MDC.put(MDC_TRACE_ID_KEY, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        response.setHeader(TRACE_ID_HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_CORRELATION_ID_KEY);
            MDC.remove(MDC_TRACE_ID_KEY);
        }
    }
}
