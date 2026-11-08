package com.linkforge.common.web;

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
 * S/N wave (audit N1): one correlation id per request. Generated here, at
 * the very head of the filter chain, and carried three ways: the MDC (log
 * lines pick it up through the {@code [%X{cid}]} pattern), a request
 * attribute (the exception handler reads it when logging a failure), and
 * the {@code X-Correlation-Id} response header (a client can quote it, and
 * support can find the one server-side log line that carries the stack).
 * An inbound X-Correlation-Id is NOT honored — ids are server-minted so a
 * caller cannot smuggle arbitrary text into log lines.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "cid";
    public static final String REQUEST_ATTRIBUTE = CorrelationIdFilter.class.getName() + ".cid";
    public static final String RESPONSE_HEADER = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String cid = UUID.randomUUID().toString();
        MDC.put(MDC_KEY, cid);
        request.setAttribute(REQUEST_ATTRIBUTE, cid);
        response.setHeader(RESPONSE_HEADER, cid);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** The id minted for this request, or null outside the filter's scope. */
    public static String currentId(HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute(REQUEST_ATTRIBUTE);
        return value instanceof String s ? s : MDC.get(MDC_KEY);
    }
}
