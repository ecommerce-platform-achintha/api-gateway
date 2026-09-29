package com.achintha.apigateway.fallback;

import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

/**
 * Target of each route's CircuitBreaker fallbackUri (forward:/fallback/{service}). Reached when the downstream
 * call fails (connection refused, no instance in Eureka, 3s time limit) or while the breaker is open.
 */
@RestController
public class FallbackController {

    private static final Logger log = LoggerFactory.getLogger(FallbackController.class);

    // Only echo known names back: /fallback/** is also reachable directly
    private static final Set<String> SERVICES = Set.of("user-service", "product-service", "order-service");

    // No method restriction: the forward keeps the original method (POST /api/orders lands here as a POST)
    @RequestMapping("/fallback/{service}")
    public ResponseEntity<Map<String, String>> fallback(@PathVariable String service, ServerWebExchange exchange) {
        String name = SERVICES.contains(service) ? service : "service";
        Throwable cause = exchange.getAttribute(ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
        if (cause != null) {
            log.warn("Fallback for {}: {}", name, cause.toString());
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", name + " is temporarily unavailable"));
    }
}
