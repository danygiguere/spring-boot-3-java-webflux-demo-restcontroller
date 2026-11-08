package com.example.restcontrollerdemo.filter;

import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Stamps every request with a server-generated correlation id, echoed in the {@value #CORRELATION_ID_HEADER} response
 * header and reused by the error handler, so a problem-detail {@code correlationId} matches the header and the log
 * lines. Inbound values are ignored (not trusted into logs).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter implements WebFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        exchange.getResponse()
                .getHeaders()
                .set(CORRELATION_ID_HEADER, UUID.randomUUID().toString());
        return chain.filter(exchange);
    }

    /** The id stamped by this filter; a fresh one if the exchange bypassed it. */
    public static String of(ServerWebExchange exchange) {
        String existing = exchange.getResponse().getHeaders().getFirst(CORRELATION_ID_HEADER);
        if (existing != null) {
            return existing;
        }
        String created = UUID.randomUUID().toString();
        exchange.getResponse().getHeaders().set(CORRELATION_ID_HEADER, created);
        return created;
    }
}
