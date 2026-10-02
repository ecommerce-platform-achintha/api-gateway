package com.achintha.apigateway.fallback;

import java.net.URI;
import java.util.Set;

import com.achintha.apigateway.support.ApiErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

/**
 * Target of each route's CircuitBreaker fallbackUri (forward:/fallback/{service}). Reached when the downstream
 * call fails (connection refused, no instance in Eureka, 3s time limit) or while the breaker is open. Clients can't
 * call /fallback/** directly (RequestGuardFilter answers 404).
 */
@RestController
public class FallbackController {

    private static final Logger log = LoggerFactory.getLogger(FallbackController.class);

    private static final Set<String> SERVICES =
            Set.of("user-service", "store-service", "product-service", "order-service");

    // No method restriction: the forward keeps the original method (POST /api/customer/checkout lands here as a POST)
    @RequestMapping("/fallback/{service}")
    public ResponseEntity<String> fallback(@PathVariable String service, ServerWebExchange exchange) {
        String name = SERVICES.contains(service) ? service : "service";
        Throwable cause = exchange.getAttribute(ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
        if (cause != null) {
            log.warn("Fallback for {}: {}", name, cause.toString());
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiErrors.json(HttpStatus.SERVICE_UNAVAILABLE, ApiErrors.SERVICE_UNAVAILABLE,
                        name + " is temporarily unavailable", originalPath(exchange)));
    }

    /** The path the client called, not /fallback/... */
    private static String originalPath(ServerWebExchange exchange) {
        Set<URI> originals = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR);
        if (originals != null && !originals.isEmpty()) {
            return originals.iterator().next().getPath();
        }
        return exchange.getRequest().getPath().value();
    }
}
