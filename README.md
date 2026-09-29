# api-gateway

Single entry point for the platform. It routes `/api/...` requests to **user-service**, **product-service** and
**order-service**, which it finds through Eureka. It is a reactive Spring Cloud Gateway (WebFlux on Netty), not a
servlet app.

- Java 25, Spring Boot 4.1.1, Spring Cloud 2025.1.3 (Gateway 5.0), Maven
- Routes in `application.yml`, resolved through Eureka (`lb://<service-id>`, no hardcoded hosts)
- JWT authentication: verifies user-service's access tokens and passes `X-User-Id` / `X-User-Roles` downstream
- Resilience4j circuit breaker per route, with a JSON 503 fallback
- In-memory rate limiting (100 requests/second per route) with Gateway's built-in `RequestRateLimiter`
- Global CORS for local frontend development
- Config from the Config Server (`optional:configserver:http://localhost:8888`), including the JWT secret
- Port **8080**

> **Dependency names in Spring Cloud 2025.1.** Gateway 5.0 no longer ships `spring-cloud-starter-gateway`. The
> reactive gateway is now `spring-cloud-starter-gateway-server-webflux`, and its properties moved from
> `spring.cloud.gateway.*` to `spring.cloud.gateway.server.webflux.*`. The circuit breaker starter is
> `spring-cloud-starter-circuitbreaker-reactor-resilience4j`, because the plain `-resilience4j` starter has no
> reactive circuit breaker and the gateway's `CircuitBreaker` filter can't work without one. Don't add
> `spring-boot-starter-web`: a servlet stack conflicts with the gateway.

## Routes

| Path | Routed to | Circuit breaker | Fallback |
|---|---|---|---|
| `/api/users/**`, `/api/auth/**` | `lb://user-service` | `userService` | `503 {"error": "user-service is temporarily unavailable"}` |
| `/api/products/**`, `/api/categories/**` | `lb://product-service` | `productService` | `503 {"error": "product-service is temporarily unavailable"}` |
| `/api/orders/**` | `lb://order-service` | `orderService` | `503 {"error": "order-service is temporarily unavailable"}` |
| `/<SERVICE-ID>/**` (e.g. `/USER-SERVICE/api/users/me`) | that service, prefix stripped | none | none |

Paths are forwarded unchanged: `/api/orders/1` reaches order-service as `/api/orders/1`.

The last row comes from the **discovery locator**. It creates a route for every service registered in Eureka and
serves as a fallback for debugging. Clients should use the `/api/...` routes, which have cleaner URLs and a
circuit breaker.

## Authentication

`JwtAuthenticationFilter` is a global filter that runs before the rate limiter, the circuit breaker and routing.
Every request to a routed path needs a valid access token from user-service, except the public routes:

| Access | Method | Path |
|---|---|---|
| **public** | POST | `/api/auth/login` |
| **public** | POST | `/api/users/register` |
| protected (`Authorization: Bearer <token>`) | any | everything else: `/api/users/**`, `/api/products/**`, `/api/categories/**`, `/api/orders/**`, the `/<SERVICE-ID>/**` debug routes |

Only the listed method is public: e.g. `GET /api/auth/login` still needs a token. The list lives in
`application.yml` (`api-gateway.auth.public-routes`, Spring path patterns such as `/api/public/**`). An entry
without `method` allows any method. `/actuator/**` and CORS preflight (`OPTIONS`) requests aren't routed, so the
filter doesn't apply to them.

The gateway verifies tokens exactly as user-service does:

- HS256 signature, using the UTF-8 bytes of the shared secret
- `exp`/`nbf` checks with 60 s clock skew
- `iss` must be `user-service` (`api-gateway.auth.issuer`)

The implementation is the same too: Spring Security's Nimbus JOSE support (`spring-security-oauth2-jose`). The
gateway uses only that module, not Spring Security's web filter chain.

| Request | Response |
|---|---|
| No `Authorization` header, or not `Bearer <token>` | **401** `{"error": "Authentication required"}` |
| Bad signature, expired, wrong issuer, unparseable, no `sub` | **401** `{"error": "Invalid or expired token"}` (the same body in every case, so the reason isn't revealed) |
| Valid token | Forwarded with `X-User-Id: <sub>` and `X-User-Roles: <roles claim, comma-separated>` (e.g. `ROLE_CUSTOMER`) |

401 responses also carry `WWW-Authenticate: Bearer` and the usual CORS headers.

Clients can't forge an identity. The gateway removes any incoming `X-User-Id` / `X-User-Roles` headers from every
request, public ones included, before it sets its own. The services can therefore trust these headers, as long as
they are only reachable through the gateway.

### Where the secret comes from

The gateway reads the secret from `security.jwt.secret`, the same key user-service reads. The key name is set in
`api-gateway.auth.secret-property`. The Config Server supplies the value from
`config-server/config-repo/api-gateway.yml`:

```yaml
security:
  jwt:
    secret: ${JWT_SECRET:local-dev-only-jwt-secret-change-me-0123456789}
```

This line must stay **identical** to the one in `config-repo/user-service.yml`, fallback included. The Config
Server only serves `user-service.yml` to user-service, so the gateway needs its own copy. The placeholder is
resolved by each service, so outside local development set the **same `JWT_SECRET`** in both services'
environments.

There is no local default. If the gateway can't get the secret (Config Server down, key missing, or fewer than
32 bytes), it **fails at startup** rather than accepting or rejecting everything.

### Circuit breakers

Each route has its own breaker, with the same settings as order-service's `productService` breaker:

| Setting | Value |
|---|---|
| count-based sliding window / min calls | 10 / 5 |
| failure-rate threshold | 50 % |
| slow call (counts as failure) | ≥ 2 s |
| open-state wait → half-open trial calls | 15 s → 3 |
| time limit per call | 3 s (Netty response timeout 4 s as a backstop, connect timeout 1 s) |

These cases trigger the fallback:

- the service has no instance in Eureka
- the connection is refused (the service is stopped but Eureka still lists it)
- the call takes longer than 3 s
- the breaker is open

A response from the service itself, including a 4xx or 5xx, passes through unchanged and doesn't count as a
failure.

Spring Cloud CircuitBreaker normally wraps every breaker in a bulkhead that allows 25 concurrent calls. This
gateway turns that off (`spring.cloud.circuitbreaker.bulkhead.resilience4j.enabled: false`). Otherwise a burst of
parallel requests gets 503 fallbacks from a healthy service before the rate limiter applies.

### Rate limiting

`RequestRateLimiter` is a default filter, so it applies to every route, including discovery-locator routes. It
uses Gateway's `Bucket4jRateLimiter` with buckets in a local Caffeine cache (`RateLimiterConfig`), not Redis.

- There is one bucket per route, shared by all clients: 100 tokens, refilled to 100 every second.
- Each response carries `X-RateLimit-Remaining`. Over the limit, the gateway returns **429 Too Many Requests**.
- Buckets live in memory, so each gateway instance has its own limit. With several instances, switch to a
  Redis-backed limiter.
- For per-client limits, have the `KeyResolver` in `RateLimiterConfig` return `routeId + ":" + clientIp`.

### CORS

The gateway applies one global CORS configuration to all paths, including preflight requests for unmatched paths:

- methods: `GET, POST, PUT, PATCH, DELETE, OPTIONS`
- headers: any; credentials allowed; max age 1 h
- origins: `http://localhost:*` and `http://127.0.0.1:*` by default. Change them in `application.yml`
  (`spring.cloud.gateway.server.webflux.globalcors`) or set `GATEWAY_CORS_ALLOWED_ORIGINS` to a comma-separated
  list of origin patterns.

If a downstream service also sends CORS headers, the gateway drops the duplicates (`DedupeResponseHeader`).

## Prerequisites

Start these **before** the gateway:

| Dependency | Default location | Notes |
|---|---|---|
| Config Server | `http://localhost:8888` | `../config-server`. **Required**: it supplies the JWT secret (`config-repo/api-gateway.yml`), and the gateway won't start without it |
| Eureka (service-registry) | `http://localhost:8761` | `../service-registry`. Required: routes are resolved through it |
| At least one downstream service | registered in Eureka as `user-service` (8081), `product-service` (8082) or `order-service` (8083) | See each service's README for its own dependencies. Routes to a service that isn't running return the 503 fallback |

You also need JDK 25 and Maven 3.9+.

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `CONFIG_SERVER_URL` | `http://localhost:8888` | Config Server |
| `EUREKA_URL` | `http://localhost:8761/eureka/` | Eureka `defaultZone` |
| `GATEWAY_CORS_ALLOWED_ORIGINS` | `http://localhost:*,http://127.0.0.1:*` | Allowed CORS origin patterns |
| `JWT_SECRET` | local-dev fallback in `config-repo/api-gateway.yml` | HS256 secret. Must be the same value user-service gets |

## Run locally

```bash
# 1. Config Server, Eureka and the services (each in its own terminal)
(cd ../config-server && mvn spring-boot:run)
(cd ../service-registry && mvn spring-boot:run)
(cd ../user-service && mvn spring-boot:run)      # and/or product-service, order-service

# 2. The gateway
mvn spring-boot:run
```

Wait until the services show up in the Eureka dashboard (http://localhost:8761). Registration and the
gateway's Eureka cache can take up to ~30 s after a service starts. Until then, its routes return the 503
fallback.

### Docker

```bash
docker build -t api-gateway .
docker run -p 8080:8080 \
  -e CONFIG_SERVER_URL=http://host.docker.internal:8888 \
  -e EUREKA_URL=http://host.docker.internal:8761/eureka/ \
  -e JWT_SECRET="$JWT_SECRET" \
  api-gateway
```

The image is multi-stage and runs as a non-root `spring` user. Services register in Eureka by IP, so the
container must be able to reach those IPs.

## Verify the routing

```bash
# All routes (the 3 explicit ones + one per service registered in Eureka), with their predicates and URIs
curl -s localhost:8080/actuator/gateway/routes | jq '.[] | {route_id, predicate, uri}'

# Health, and each route's circuit breaker state
curl -s localhost:8080/actuator/health | jq
curl -s localhost:8080/actuator/circuitbreakers | jq
```

The gateway actuator endpoint is **read-only**: routes can be inspected but not added or refreshed over HTTP.

## Example requests through the gateway

These are the same calls as in each service's README, but sent to port 8080. Register and log in first, since
every other call needs the token.

```bash
# --- user-service (/api/users/**, /api/auth/**) ---
# Public: no token needed
curl -i -X POST localhost:8080/api/users/register -H 'Content-Type: application/json' \
  -d '{"email":"jane@example.com","password":"Str0ng!Passw0rd","firstName":"Jane","lastName":"Doe"}'

TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"jane@example.com","password":"Str0ng!Passw0rd"}' | jq -r .accessToken)
AUTH="Authorization: Bearer $TOKEN"

curl -s localhost:8080/api/users/me -H "$AUTH" | jq

# --- Rejected requests ---
# No token -> 401
curl -i localhost:8080/api/users/me
# HTTP/1.1 401 Unauthorized
# WWW-Authenticate: Bearer
# {"error":"Authentication required"}

# Wrong scheme -> 401, same body
curl -i localhost:8080/api/orders -H 'Authorization: Basic dXNlcjpwYXNz'

# Tampered, expired or otherwise invalid token -> 401
curl -i localhost:8080/api/users/me -H "Authorization: Bearer ${TOKEN%?}x"
# HTTP/1.1 401 Unauthorized
# {"error":"Invalid or expired token"}

# --- product-service (/api/products/**, /api/categories/**) ---
CATEGORY_ID=$(curl -s -X POST localhost:8080/api/categories -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"name": "Electronics"}' | jq -r .id)
PRODUCT_ID=$(curl -s -X POST localhost:8080/api/products -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"name\": \"Gaming Laptop\", \"price\": 999.99, \"sku\": \"LAP-001\", \"categoryId\": \"$CATEGORY_ID\"}" | jq -r .id)
curl -s -X PATCH localhost:8080/api/products/$PRODUCT_ID/inventory -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"delta": 10}' | jq
curl -s localhost:8080/api/categories -H "$AUTH" | jq

# --- order-service (/api/orders/**) ---
USER_ID=$(uuidgen)
ORDER_ID=$(curl -s -X POST localhost:8080/api/orders -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"userId\": \"$USER_ID\", \"items\": [{\"productId\": \"$PRODUCT_ID\", \"quantity\": 2}]}" | jq -r .id)
curl -s localhost:8080/api/orders/$ORDER_ID -H "$AUTH" | jq

# --- discovery-locator fallback route (debugging only) ---
curl -s localhost:8080/PRODUCT-SERVICE/api/categories -H "$AUTH" | jq

# --- CORS preflight from a local frontend ---
curl -si -X OPTIONS localhost:8080/api/products \
  -H 'Origin: http://localhost:5173' -H 'Access-Control-Request-Method: POST' | grep -i '^access-control'
```

To see the rate limiter, fire a burst at one route. Some requests should come back as 429:

```bash
seq 1 300 | xargs -P 50 -I{} curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/api/categories -H "$AUTH" | sort | uniq -c
```

## Observing a route's circuit breaker

1. With the gateway and order-service running, check the breaker:
   `curl -s localhost:8080/actuator/circuitbreakers | jq .circuitBreakers.orderService` → `"state": "CLOSED"`.
2. **Stop order-service** (Ctrl+C its `mvn spring-boot:run`).
3. Call its route. Expect a **503** straight away, not a hang:
   ```bash
   curl -i localhost:8080/api/orders/$ORDER_ID -H "$AUTH"
   # HTTP/1.1 503 Service Unavailable
   # {"error":"order-service is temporarily unavailable"}
   ```
   While Eureka still lists the stopped instance, the connection is refused. Once Eureka drops it (up to ~90 s),
   the load balancer finds no instance. Either way the response is the 503 fallback, and the gateway logs the
   cause (`Fallback for order-service: ...`).
4. Repeat 5 times. At ≥ 50 % failures the breaker **opens** (`/actuator/circuitbreakers` shows `OPEN`). Requests
   now go straight to the fallback without calling order-service. `/actuator/circuitbreakerevents` lists each
   call and state change.
5. The other routes keep working, because each route has its own breaker.
6. Start order-service again. After 15 s the breaker goes half-open, lets 3 trial calls through, and closes if
   they succeed.

To see the **3 s timeout**, keep the service running but make it slow (e.g. pause it in a debugger). The gateway
returns the 503 fallback after about 3 s.

## Tests

```bash
mvn clean package
```

`JwtAuthenticationFilterTest` tests the filter on its own. It uses the real decoder from `JwtConfig`, with tokens
signed the way user-service signs them:

- a valid token passes through with `X-User-Id` = `sub` and `X-User-Roles` = the comma-joined `roles` claim
- `X-User-Id` / `X-User-Roles` sent by the client are replaced on protected routes and removed on public ones
- missing or malformed `Authorization` header → 401 `Authentication required`
- expired, wrongly signed, wrong-issuer or unparseable token → 401 `Invalid or expired token`
- `POST /api/auth/login` and `POST /api/users/register` pass with no token; `GET /api/auth/login` doesn't

`RouteConfigurationTest` runs without Config Server, Eureka or any downstream service. The JWT secret is set as a
test property. It checks the configuration without making real requests to the services:

- exactly three explicit routes, each with the expected `lb://` URI and `Path` patterns
- each route has a `CircuitBreaker` filter with the right breaker name and `forward:/fallback/<service>`
- sample paths (`/api/auth/login`, `/api/users/me/addresses`, `/api/categories`, ...) match exactly one expected
  route, and unknown `/api/...` paths match none
- `RequestRateLimiter` (100/s) is a default filter and the discovery locator is enabled
- no service is registered in the test, so a real request to each route checks the circuit breaker wiring: the
  response is the JSON 503 fallback with an `X-RateLimit-Remaining` header
- through the running gateway, a protected route without a token gets 401, and the public login route is routed
  without one

End-to-end tests against the real services aren't practical here, because all three would have to be running.

## Known limitations / next steps

- **The services are still reachable directly** (e.g. `localhost:8083`), bypassing the gateway. Before they rely
  on `X-User-Id` / `X-User-Roles`, only the gateway should be able to reach them (network isolation, or a check
  that the request came from the gateway).
- The gateway checks only that the caller is authenticated, not their roles. Role-based rules (e.g. admin-only
  product writes) are left to the services, which get `X-User-Roles`.
- The `/<SERVICE-ID>/**` debug routes need a token like everything else, including their login/register paths.
- Rate limits are per gateway instance and per route, not per client (see *Rate limiting*).
- The discovery locator exposes every registered service under `/<SERVICE-ID>/**`, without a circuit breaker.
  That is useful for debugging, but it could be disabled or restricted with `include-expression` in production.
