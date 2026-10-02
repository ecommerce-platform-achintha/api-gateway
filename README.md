# api-gateway

Single entry point for the marketplace. It routes `/api/...` requests to **user-service**, **store-service**,
**product-service** and **order-service**, which it finds through Eureka. It is a reactive Spring Cloud Gateway
(WebFlux on Netty), not a servlet app.

The gateway is the **first line of defence only**. Every service validates the JWT again and enforces the real
authorization rules (see `docs/marketplace-design.md`, sections 3 and 11). The gateway holds no business logic.

- Java 25, Spring Boot 4.1.1, Spring Cloud 2025.1.3 (Gateway 5.0), Maven
- RS256 JWT check against user-service's JWKS (cached), with issuer, audience and expiry checks
- Public-route list and coarse path-prefix role checks, both from config
- `/internal/**` is never reachable through the gateway
- Per-client rate limits (user id, else client IP) in tiers, with `429` and `Retry-After`
- CORS from an explicit origin list, security headers, and a request body size limit
- Resilience4j circuit breaker per route, with a JSON 503 fallback
- Config from the Config Server (`optional:configserver:http://localhost:8888`)
- Port **8080**

> **Dependency names in Spring Cloud 2025.1.** Gateway 5.0 no longer ships `spring-cloud-starter-gateway`. The
> reactive gateway is now `spring-cloud-starter-gateway-server-webflux`, and its properties moved from
> `spring.cloud.gateway.*` to `spring.cloud.gateway.server.webflux.*`. The circuit breaker starter is
> `spring-cloud-starter-circuitbreaker-reactor-resilience4j`. Don't add `spring-boot-starter-web`: a servlet stack
> conflicts with the gateway.

## Request pipeline

Every request passes these steps in order. The first one that rejects the request answers it.

| # | Step | Applies to | Rejects with |
|---|---|---|---|
| 1 | `SecurityHeadersFilter` | every response | – (adds headers) |
| 2 | `CorsWebFilter` | every request; answers preflights itself | 403 for an unlisted origin, method or header |
| 3 | `RequestGuardFilter` | every request | 400 `.`/`..`/encoded-slash path segments, 404 `/internal/**` and `/fallback/**`, 413 body too large, 411 chunked body |
| 4 | Route matching | | 404 if no route matches |
| 5 | `JwtAuthenticationFilter` | routed requests | 401 missing/invalid token, 403 wrong role for the path prefix, 503 if signing keys can't be fetched |
| 6 | `RateLimitFilter` | routed requests | 429 + `Retry-After` |
| 7 | `CircuitBreaker` (per route) | routed requests | 503 fallback if the service is down or slow |
| 8 | `DownstreamPathGuardFilter` | the final downstream URL | 404 if a rewrite would reach `/internal/**` |

All gateway errors use the platform's standard error body:

```json
{"timestamp":"2026-10-01T08:00:00Z","status":401,"error":"Unauthorized","code":"UNAUTHORIZED",
 "message":"Authentication required: missing, invalid or expired token","path":"/api/customer/orders"}
```

| Status | `code` |
|---|---|
| 400 | `BAD_REQUEST` |
| 401 | `UNAUTHORIZED`, with `WWW-Authenticate: Bearer` |
| 403 | `ACCESS_DENIED` |
| 404 | `NOT_FOUND` |
| 411 / 413 | `LENGTH_REQUIRED` / `PAYLOAD_TOO_LARGE` |
| 429 | `RATE_LIMITED`, with `Retry-After` |
| 503 | `SERVICE_UNAVAILABLE` |

## Authentication model

user-service signs access tokens with **RS256**, and its private key never leaves it. The public keys are published
at `GET /.well-known/jwks.json`. The gateway, like every service, verifies tokens against that JWKS. There is **no
shared secret** any more.

`JwtConfig` builds a `NimbusReactiveJwtDecoder` that checks:

- the signature, with **RS256 only**. This rejects `alg=none` and HS256 tokens, including HS256 tokens "signed"
  with the public key (the algorithm-confusion attack)
- `iss` = `security.jwt.issuer` (`user-service`)
- `aud` contains `security.jwt.audience` (`marketplace`)
- `exp` must be present; `exp`/`nbf` are checked with **30 s** clock skew
- `sub` and `role` must not be blank

Keys are cached by `JwksCache`:

- The key set is fetched on first use and re-fetched on the next request after **5 min**.
- A token whose `kid` isn't cached (key rotation) triggers an immediate re-fetch, at most once every **10 s**. This
  stops tokens with made-up key ids from flooding user-service with JWKS requests.
- If a re-fetch fails, the cached keys are still used.
- If no keys were ever fetched (user-service down at first use), protected routes answer **503**, not 401.

Every 401 has the same body, whatever the reason (missing, malformed, bad signature, expired, wrong
issuer/audience). Rejections are logged at DEBUG with the reason only. **Tokens are never logged.**

The gateway doesn't check the token version (`tv`) against revocations. Each service does that with its
`user_security_state` cache. Access tokens live 10 minutes.

### Identity headers

The gateway **no longer injects `X-User-Id` / `X-User-Roles`.** It forwards the `Authorization` header unchanged,
and each service derives the identity from the JWT itself. Any `X-User-Id` / `X-User-Roles` sent by a client is
**removed from every request**, public ones included, so a service that still read them couldn't be fooled.

### Public routes

These requests need no token (`api-gateway.auth.public-routes`). Only the listed method is public:
`GET /api/auth/login` or `POST /api/public/...` still need a token.

| Method | Path |
|---|---|
| POST | `/api/auth/login` |
| POST | `/api/auth/refresh` |
| POST | `/api/users/register/customer` |
| POST | `/api/users/register/merchant` |
| GET | `/.well-known/jwks.json` |
| GET | `/api/public/**` |

Everything else needs `Authorization: Bearer <access token>`.

### Path-prefix role checks

These are coarse checks (`api-gateway.auth.role-rules`) on the token's `role` claim. They're defence in depth: the
services still enforce every rule, including assistant permissions and ownership. The first matching rule decides.
Paths that match no rule (e.g. `/api/users/me`, `/api/auth/logout`) only need a valid token. A wrong role gets
**403** `ACCESS_DENIED`.

| Path | Allowed roles |
|---|---|
| `/api/customer/**` | `ROLE_CUSTOMER` |
| `/api/merchant/owner/**` | `ROLE_MERCHANT` (owner-only, design 3.4) |
| `/api/merchant/**` | `ROLE_MERCHANT`, `ROLE_ASSISTANT` |
| `/api/admin/**` | `ROLE_ADMIN`, `ROLE_SUPER_ADMIN` |
| `/api/super-admin/**` | `ROLE_SUPER_ADMIN` |

## Routes

Each service has one route with its own circuit breaker and fallback. Paths are forwarded unchanged. The route
patterns **never overlap**, so a request matches at most one route. `RouteConfigurationTest` checks this with a
sample path for each endpoint group. Each route also has a fixed `order` (10/20/30/40).

| Route → service | Paths | Breaker |
|---|---|---|
| **user-service** | `/api/auth/**`, `/api/users/**`, `/api/merchant/application/**`, `/api/merchant/owner/assistants/**`, `/api/admin/users/**`, `/api/admin/merchants/pending`, `/api/admin/merchants/*/{approve,reject,ban,unban}`, `/api/admin/customers/*/{ban,unban}`, `/api/super-admin/admins/**`, `/.well-known/jwks.json` | `userService` |
| **store-service** | `/api/public/stores/**`, `/api/merchant/store/**`, `/api/merchant/owner/{bank-accounts,couriers,shipping-templates,metrics}/**`, `/api/merchant/reviews/**`, `/api/customer/stores/**`, `/api/admin/stores/**`, `/api/admin/merchants/at-risk`, `/api/admin/banks/**`, `/api/admin/couriers/**`, `/api/super-admin/{settings,holidays,merchants}/**` | `storeService` |
| **product-service** | `/api/public/products/**`, `/api/public/categories/**`, `/api/merchant/products/**`, `/api/merchant/variants/**`, `/api/merchant/discounts/**`, `/api/customer/products/**`, `/api/admin/products/**`, `/api/admin/categories/**`, `/api/admin/reviews/**` | `productService` |
| **order-service** | `/api/customer/cart/**`, `/api/customer/checkout`, `/api/customer/orders/**`, `/api/customer/complaints/**`, `/api/merchant/orders/**` (incl. `/complaints`), `/api/merchant/customer-blocks/**`, `/api/admin/orders/**`, `/api/admin/complaints/**`, `/api/admin/flagged-references/**`, `/api/admin/customers/*/score` | `orderService` |

Overlaps were resolved by using the exact paths the services implement:

- `/api/admin/customers/*/score` → order-service, and `/api/admin/customers/*/ban|unban` → user-service, rather than
  a broad `/api/admin/customers/**`.
- `/api/admin/merchants/at-risk` → store-service, and `/api/admin/merchants/pending` and `/*/approve|reject|ban|unban`
  → user-service.
- `/api/merchant/products/reviews/**` stays inside product-service's `/api/merchant/products/**`, while store reviews
  are under `/api/merchant/reviews/**` (store-service).

### `/internal/**` is never reachable

- No route matches `/internal/**`.
- `RequestGuardFilter` answers `/internal/**` with 404 before routing. It compares decoded segments, ignoring case,
  `;matrix` parameters and empty `//` segments.
- Paths with `.`/`..` or encoded `/`/`\` segments get 400, because a servlet container would normalise
  `/api/public/../../internal/x` into `/internal/x`.
- `DownstreamPathGuardFilter` checks the final downstream URL after every rewrite. This covers the discovery
  locator's `/USER-SERVICE/internal/...`.

### Discovery-locator debug routes

`/<SERVICE-ID>/**` routes, one per service in Eureka, are enabled **only in the `local` profile**
(`application-local.yml`), along with the `gateway` actuator endpoint. They need a token, have no circuit breaker,
and can't reach `/internal/**`. They see the prefixed path, so the role-prefix rules don't apply to them (the
services' own rules still do).

### Circuit breakers

There is one breaker per route (`userService`, `storeService`, `productService`, `orderService`), all with the same
settings:

| Setting | Value |
|---|---|
| count-based sliding window / min calls | 10 / 5 |
| failure-rate threshold | 50 % |
| slow call (counts as failure) | ≥ 2 s |
| open-state wait → half-open trial calls | 15 s → 3 |
| time limit per call | 3 s (Netty response timeout 4 s as a backstop, connect timeout 1 s) |

The fallback answers **503** `{"code":"SERVICE_UNAVAILABLE","message":"<service> is temporarily unavailable",...}`
when:

- the service has no instance in Eureka
- the connection is refused
- the call exceeds 3 s
- the breaker is open

A response from the service itself, including a 4xx/5xx, passes through unchanged. The Resilience4j bulkhead is
off (`spring.cloud.circuitbreaker.bulkhead.resilience4j.enabled: false`), so bursts are limited by the rate limiter,
not by 25-call bulkheads.

## Rate limiting

`RateLimitFilter` limits **per client, not per route**. It runs after authentication.

- **Key**: for `user-or-ip` tiers, the authenticated user id (`sub`), otherwise the client IP. `ip` tiers always
  use the IP.
- **Client IP**: the TCP peer address. `X-Forwarded-For` is used only when the peer is in
  `api-gateway.rate-limit.trusted-proxies` (IPs or CIDRs, env `GATEWAY_TRUSTED_PROXIES`). The client is then the
  right-most address that isn't itself a trusted proxy. Otherwise a client could pick a fresh IP for every
  request.
- **Tiers** (`api-gateway.rate-limit.tiers`): the first tier with a matching route applies, else `default`.

| Tier | Requests | Limit | Key |
|---|---|---|---|
| `auth` (strict) | `POST /api/auth/login`, `POST /api/auth/refresh`, `POST /api/users/register/**` | 10 / min | IP |
| `sensitive` (moderate) | `POST /api/customer/checkout`, `POST /api/customer/orders/*/payments/**` | 20 / min | user, else IP |
| `public` (generous) | `GET /api/public/**` | 300 / min | user, else IP |
| `default` | everything else | 120 / min | user, else IP |

Buckets refill gradually (greedy refill), so a client that hits the limit can retry after a few seconds rather
than a full minute. Every limited response carries `X-RateLimit-Remaining`. Over the limit:

```
HTTP/1.1 429 Too Many Requests
Retry-After: 6
X-RateLimit-Remaining: 0
{"status":429,"error":"Too Many Requests","code":"RATE_LIMITED","message":"Too many requests, retry after 6 s",...}
```

Requests rejected with 401/403 are not counted. Unauthenticated callers only reach public routes, which have their
own IP-keyed limits.

> **Multiple gateway instances:** buckets live in each instance's memory (Bucket4j + Caffeine, up to 100,000
> clients, idle buckets evicted). With N instances behind a load balancer, a client effectively gets up to N× the
> limit. Run more than one instance only with a shared limiter, for example Bucket4j's Redis
> (Lettuce/Redisson) proxy manager in `RateLimitFilter` or a Redis-backed `RequestRateLimiter`.

## CORS

CORS is answered at the gateway only (`security.cors.*`, origins from the Config Server per environment):

| Setting | Value |
|---|---|
| allowed origins | `security.cors.allowed-origins` (env `CORS_ALLOWED_ORIGINS`). **Exact origins only**: `*` or a pattern fails startup |
| allowed methods | `GET, POST, PUT, PATCH, DELETE, OPTIONS` |
| allowed headers | `Authorization, Content-Type, Idempotency-Key` |
| exposed headers | `Retry-After, X-RateLimit-Remaining` |
| credentials | allowed, which is safe because they only go to the listed origins |
| max age | 1 h |

A preflight from an unlisted origin, or one asking for an unlisted header such as `X-User-Id`, gets 403. CORS
headers a service might still send are de-duplicated (`DedupeResponseHeader`).

## Security headers and limits

- Every response: `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY`.
- `/api/auth/**` responses (tokens): `Cache-Control: no-store`, `Pragma: no-cache`.
- These are set just before the response is sent, so they apply to service responses, fallbacks and the
  gateway's own errors alike.
- Request bodies are limited to `api-gateway.request.max-body-size` (**1 MB**) by `Content-Length`: larger
  bodies get 413. Bodies without `Content-Length` (chunked) get 411, since their size can't be checked up front.
- HSTS isn't set here: add it at the TLS-terminating proxy.

## API documentation

The gateway doesn't merge the services' OpenAPI documents. Each service publishes its own (internal endpoints are
excluded):

| Service | Swagger UI | OpenAPI JSON |
|---|---|---|
| user-service | http://localhost:8081/swagger-ui.html | http://localhost:8081/v3/api-docs |
| product-service | http://localhost:8082/swagger-ui.html | http://localhost:8082/v3/api-docs |
| order-service | http://localhost:8083/swagger-ui.html | http://localhost:8083/v3/api-docs |
| store-service | http://localhost:8084/swagger-ui.html | http://localhost:8084/v3/api-docs |

Use the route table above to map each documented path to the gateway (`http://localhost:8080` + the same path).

## Configuration

The gateway's `application.yml` holds local defaults. The Config Server (`config-repo/api-gateway.yml` and the
shared `application.yml`) supplies the real values, which take precedence. List properties (`public-routes`,
`role-rules`, a tier's `routes`) are replaced as a whole by a higher-precedence source.

| Env var | Default | Purpose |
|---|---|---|
| `CONFIG_SERVER_URL` | `http://localhost:8888` | Config Server |
| `EUREKA_URL` | `http://localhost:8761/eureka/` | Eureka `defaultZone` |
| `JWKS_URI` | `http://localhost:8081/.well-known/jwks.json` | user-service JWKS. `lb://user-service/.well-known/jwks.json` resolves through Eureka |
| `JWT_ISSUER` | `user-service` | Expected `iss` |
| `JWT_AUDIENCE` | `marketplace` | Required `aud` |
| `JWT_CLOCK_SKEW_SECONDS` | `30` | `exp`/`nbf` tolerance |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:5173` | Exact CORS origins, comma-separated |
| `GATEWAY_TRUSTED_PROXIES` | empty | IPs/CIDRs whose `X-Forwarded-For` is trusted (e.g. your load balancer) |
| `SPRING_PROFILES_ACTIVE` | none | `local` enables the discovery debug routes and the `gateway` actuator endpoint |

Other settings (in `application.yml`): `api-gateway.auth.jwks-cache-ttl` (PT5M),
`api-gateway.auth.jwks-refresh-cooldown` (PT10S), `api-gateway.request.max-body-size` (1MB), and the rate-limit
tiers.

## Prerequisites

| Dependency | Default location | Notes |
|---|---|---|
| Config Server | `http://localhost:8888` | `../config-server`. Optional: without it the local defaults apply |
| Eureka (service-registry) | `http://localhost:8761` | `../service-registry`. Routes are resolved through it |
| user-service | 8081 | Needed for the JWKS. Without it, protected routes answer 503 |
| store/product/order-service | 8084 / 8082 / 8083 | Routes to a service that isn't running return the 503 fallback |

You also need JDK 25 and Maven 3.9+.

## Run locally

```bash
(cd ../config-server && mvn spring-boot:run)
(cd ../service-registry && mvn spring-boot:run)
(cd ../user-service && mvn spring-boot:run)     # then store-service, product-service, order-service

SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

Services can take up to ~30 s to appear in the gateway's Eureka cache. Until then their routes return the 503
fallback.

### Docker

```bash
docker build -t api-gateway .
docker run -p 8080:8080 \
  -e CONFIG_SERVER_URL=http://host.docker.internal:8888 \
  -e EUREKA_URL=http://host.docker.internal:8761/eureka/ \
  -e JWKS_URI=lb://user-service/.well-known/jwks.json \
  -e GATEWAY_TRUSTED_PROXIES=10.0.0.0/8 \
  api-gateway
```

## Example requests

```bash
# Public: register and log in (no token)
curl -s -X POST localhost:8080/api/users/register/customer -H 'Content-Type: application/json' -d @customer.json
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"jane@example.com","password":"a-long-Passw0rd!"}' | jq -r .accessToken)
AUTH="Authorization: Bearer $TOKEN"

curl -s localhost:8080/api/users/me -H "$AUTH" | jq
curl -s 'localhost:8080/api/public/products?q=laptop' | jq           # public browsing
curl -s localhost:8080/api/customer/cart -H "$AUTH" | jq

# Rejections
curl -i localhost:8080/api/customer/orders                           # 401 UNAUTHORIZED
curl -i localhost:8080/api/admin/orders -H "$AUTH"                   # 403 ACCESS_DENIED (customer token)
curl -i localhost:8080/internal/users/x/security-state               # 404 NOT_FOUND
for i in $(seq 1 11); do curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/auth/login; done
                                                                      # ... 429 with Retry-After on the 11th

# CORS preflight
curl -si -X OPTIONS localhost:8080/api/customer/checkout -H 'Origin: http://localhost:5173' \
  -H 'Access-Control-Request-Method: POST' -H 'Access-Control-Request-Headers: Idempotency-Key' | grep -i '^access-control'

# Health and breakers (local profile also: /actuator/gateway/routes)
curl -s localhost:8080/actuator/health | jq
curl -s localhost:8080/actuator/circuitbreakers | jq
```

## Tests

```bash
mvn clean package
```

No Config Server, Eureka or service is needed. The JWKS comes from an in-test HTTP server (`JwksTestServer`), and
tokens are signed the way user-service signs them (`TestTokens`).

- **`JwtAuthenticationFilterTest`** (the real decoder):
  - A valid RS256 token passes, with `Authorization` forwarded unchanged and no identity headers added.
  - These are all rejected with the same 401: wrong signature, unknown `kid`, `alg=none`, HS256 (including signed
    with the public key), wrong issuer, wrong or missing audience, expired beyond 30 s, not yet valid, no `exp`,
    no `role`, missing or malformed header.
  - A token that expired within the 30 s skew is accepted.
  - Spoofed `X-User-Id`/`X-User-Roles` are stripped on protected and public routes.
  - Public routes pass without a token, but only for their method.
  - The role-prefix matrix gives 403 with the standard body.
  - An unreachable JWKS gives 503.
- **`JwksCacheTest`**: keys are cached, re-fetched after the TTL, and re-fetched on an unknown `kid` at most once
  per cooldown. Stale keys are kept when a refresh fails, and a missing key set is reported as unavailable.
- **`RateLimitFilterTest`**:
  - Each client gets its own bucket, keyed by user when authenticated and by IP otherwise.
  - Login is stricter than the default tier and is always IP-keyed.
  - The limit gives 429 with `Retry-After` and the standard body.
  - `X-Forwarded-For` is honoured only from trusted proxies.
  - Checkout and payment share the moderate tier.
- **`RequestGuardFilterTest`**:
  - `/internal/**` variants (case, `//`, `;matrix`, percent-encoding) and `/fallback/**` give 404.
  - `..`, `%2e%2e` and encoded slashes give 400.
  - A body over the limit gives 413, and a chunked body gives 411.
- **`RouteConfigurationTest`** (the whole gateway):
  - There are four `lb://` routes, each with its own breaker and fallback.
  - 64 sample paths each match **exactly one** route, and other paths (including `/internal/**`) match none.
  - Requests to unavailable services return the 503 fallback.
  - Public routes are routed without a token. Protected ones give 401, and wrong roles give 403.
  - `/internal/**` and `/fallback/**` give 404, and path traversal gives 400.
  - Login gives 429 after 10 requests, for that client only.
  - Auth responses carry the security headers and `no-store`.
  - CORS allows the listed origin and rejects others and unlisted headers.
  - The discovery locator and the `gateway` actuator endpoint are off.
- **`LocalProfileTest`**: in the `local` profile, the discovery routes exist, need a token, and can't reach
  `/internal/**`.

## Known limitations

- Rate-limit buckets are per instance (see the note under *Rate limiting*).
- The gateway doesn't check token revocation (`tv`): services do, so a revoked token is refused by the service, not
  the gateway.
- Services are still reachable directly on their own ports. That is acceptable now because each one validates the
  JWT itself and no longer trusts identity headers, but in production only the gateway should be exposed.
