package com.achintha.apigateway.web;

import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/**
 * Security headers on every response, whoever produced it (a service, a fallback, or the gateway's own 4xx). They are
 * set just before the response is committed, so they replace any value a service sent.
 * <p>
 * Auth responses ({@code /api/auth/**}: tokens) also get {@code Cache-Control: no-store}.
 */
public class SecurityHeadersFilter implements WebFilter {

    private static final PathPattern AUTH_PATHS = PathPatternParser.defaultInstance.parse("/api/auth/**");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        boolean authResponse = AUTH_PATHS.matches(exchange.getRequest().getPath().pathWithinApplication());
        exchange.getResponse().beforeCommit(() -> {
            HttpHeaders headers = exchange.getResponse().getHeaders();
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("Referrer-Policy", "no-referrer");
            headers.set("X-Frame-Options", "DENY");
            if (authResponse) {
                headers.set(HttpHeaders.CACHE_CONTROL, "no-store");
                headers.set(HttpHeaders.PRAGMA, "no-cache");
            }
            return Mono.empty();
        });
        return chain.filter(exchange);
    }
}
