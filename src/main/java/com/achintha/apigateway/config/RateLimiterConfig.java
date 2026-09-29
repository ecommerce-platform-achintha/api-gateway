package com.achintha.apigateway.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.caffeine.Bucket4jCaffeine;
import io.github.bucket4j.distributed.proxy.AsyncProxyManager;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Backing store and key for the RequestRateLimiter filter (see default-filters in application.yml).
 * <p>
 * Defining an {@link AsyncProxyManager} bean makes Gateway auto-configure its built-in Bucket4jRateLimiter.
 * Buckets live in a local Caffeine cache, so limits are per gateway instance; swap in a Redis-backed
 * proxy manager (or RedisRateLimiter) if several gateway instances need to share them.
 */
@Configuration(proxyBeanMethods = false)
public class RateLimiterConfig {

    @Bean
    AsyncProxyManager<String> rateLimiterBuckets() {
        return Bucket4jCaffeine.<String>builderFor(Caffeine.newBuilder().maximumSize(1_000))
                .build()
                .asAsync();
    }

    /**
     * One bucket per route, shared by all clients. Bucket4jRateLimiter keys buckets by this value alone
     * (not by route), so it has to include the route id. For per-client limits, return e.g.
     * {@code routeId + ":" + clientIp} instead.
     */
    @Bean
    KeyResolver routeKeyResolver() {
        return exchange -> Mono.justOrEmpty(exchange.<Route>getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR))
                .map(Route::getId);
    }
}
