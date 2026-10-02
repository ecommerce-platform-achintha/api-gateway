package com.achintha.apigateway.web;

import java.net.URI;
import java.util.List;

import com.achintha.apigateway.support.ApiErrors;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.RouteToRequestUrlFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Checks the URL a route is about to call, after every rewrite: a request that would reach {@code /internal/**} on a
 * service gets 404. {@link RequestGuardFilter} already blocks {@code /internal/**} as sent by the client; this catches
 * routes that rewrite paths, such as the discovery locator's {@code /USER-SERVICE/internal/...} (local profile only).
 */
@Component
public class DownstreamPathGuardFilter implements GlobalFilter, Ordered {

    /** Right after the downstream URL is built, before the load balancer and the HTTP call. */
    public static final int ORDER = RouteToRequestUrlFilter.ROUTE_TO_URL_FILTER_ORDER + 1;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        URI target = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        if (target != null && target.getRawPath() != null) {
            List<String> segments = InternalPaths.segments(PathContainer.parsePath(target.getRawPath()));
            if (InternalPaths.isInternal(segments) || InternalPaths.isAmbiguous(segments)) {
                return ApiErrors.write(exchange, HttpStatus.NOT_FOUND, ApiErrors.NOT_FOUND, "Resource not found");
            }
        }
        return chain.filter(exchange);
    }
}
