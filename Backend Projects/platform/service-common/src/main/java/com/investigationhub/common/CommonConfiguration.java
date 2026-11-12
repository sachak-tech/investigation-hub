package com.investigationhub.common;

import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class CommonConfiguration {
    public static final String CORRELATION_HEADER = "X-Correlation-Id";

    @Bean
    OncePerRequestFilter correlationFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {
                String incoming = request.getHeader(CORRELATION_HEADER);
                String correlationId = incoming == null || incoming.isBlank()
                        ? UUID.randomUUID().toString() : incoming;
                response.setHeader(CORRELATION_HEADER, correlationId);
                try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", correlationId)) {
                    chain.doFilter(request, response);
                }
            }
        };
    }

    @Bean
    OutboxWriter outboxWriter(JdbcClient jdbc, JsonMapper json,
            @Value("${ih.outbox.schema:}") String schema) {
        return new OutboxWriter(jdbc, json, schema);
    }
}
