package com.achintha.apigateway.web;

import java.util.List;

import com.achintha.apigateway.support.ApiErrors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Checks every incoming request before route matching (a WebFilter, so it also covers paths no route matches and the
 * discovery-locator routes):
 * <ul>
 *   <li>{@code .}/{@code ..} or encoded-slash segments: 400. Servlet containers normalise these, so
 *       {@code /api/public/../../internal/x} would otherwise reach {@code /internal/x}.</li>
 *   <li>{@code /internal/**}: 404. Internal endpoints are service-to-service only and never routed.</li>
 *   <li>{@code /fallback/**}: 404. Only the circuit breakers may forward there (forwards skip WebFilters).</li>
 *   <li>Body larger than the limit (by {@code Content-Length}): 413. A body without {@code Content-Length}
 *       (chunked) can't be checked up front, so it gets 411.</li>
 * </ul>
 */
public class RequestGuardFilter implements WebFilter {

    private final long maxBodyBytes;

    public RequestGuardFilter(DataSize maxBodySize) {
        this.maxBodyBytes = maxBodySize.toBytes();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        List<String> segments = InternalPaths.segments(request.getPath().pathWithinApplication());

        if (InternalPaths.isAmbiguous(segments)) {
            return ApiErrors.write(exchange, HttpStatus.BAD_REQUEST, ApiErrors.BAD_REQUEST, "Invalid request path");
        }
        if (InternalPaths.isInternal(segments)
                || (!segments.isEmpty() && segments.getFirst().equalsIgnoreCase("fallback"))) {
            return ApiErrors.write(exchange, HttpStatus.NOT_FOUND, ApiErrors.NOT_FOUND, "Resource not found");
        }

        HttpHeaders headers = request.getHeaders();
        long contentLength = headers.getContentLength();
        if (contentLength > maxBodyBytes) {
            return ApiErrors.write(exchange, HttpStatus.PAYLOAD_TOO_LARGE, ApiErrors.PAYLOAD_TOO_LARGE,
                    "Request body exceeds " + maxBodyBytes + " bytes");
        }
        if (contentLength < 0 && headers.containsHeader(HttpHeaders.TRANSFER_ENCODING)) {
            return ApiErrors.write(exchange, HttpStatus.LENGTH_REQUIRED, ApiErrors.LENGTH_REQUIRED,
                    "Content-Length is required for request bodies");
        }
        return chain.filter(exchange);
    }
}
