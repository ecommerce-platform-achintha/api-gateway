# Marketplace Upgrade: Design v1

Status: final. All open points are resolved (section 13); this is the contract behind the per-repo prompts.

Baseline: Java 25, Spring Boot 4.1.1, Spring Cloud 2025.1.3, PostgreSQL, Kafka, Eureka, Config Server, Resilience4j (all as in the current repos).

---

## 0. Decision log (your answers)

| # | Decision |
|---|---|
| D1 | All timers are real (wall-clock) hours, default 24 h, and hours on public/Poya holidays do not count (holiday list kept by super admin). Only the 7-day auto-complete is in days. |
| D2 | Admins can read merchant scores, verify merchants and ban them. Low scores raise an admin alert; nothing is automatic. |
| D3 | Email is unique per user. Every user records a NIC (may repeat across users). An assistant's NIC can belong to only one store; the same person may still be a merchant or customer. First login forces a password change. |
| D4 | Banned merchant: 14 days working as usual but receiving no new orders. On day 14 the ban is announced and admins force-resolve the remaining orders. |
| D5 | Banned customer: can log in and view history, cannot buy; unconfirmed orders are cancelled with no penalty. |
| D6 | Customer may cancel free of penalty only before the merchant quotes. Rejecting a quote or letting it expire costs -1. |
| D7 | Customer +1 is awarded on completion, not on confirmation. |
| D8 | A customer's 3rd COD refusal removes COD for 3 months. |
| D9 | Duplicate bank reference: the submission is rejected and flagged to admins, and kept in a separate record. |
| D10 | Auto-complete 7 days after the order is marked shipped. |
| D11 | Platform fee (LKR 500/month + LKR 10/order, super-admin credits) is recorded in a ledger now and not enforced. |
| D12 | Human-readable IDs for every entity. Item ID is separate from the merchant's SKU. Stores have unique names. |
| D13 | Discounts: product/variant discounts plus a quote-step discount. |
| D14 | Variants managed by merchants. |
| D15 | Reviews in scope for products and stores, with merchant replies. |
| D16 | Store and item search are public. |
| D17 | New `store-service` (7th repo). |
| D18 | Databases are dev-only and can be reset once. Flyway SQL scripts are maintained from now on (V1 baseline, then V2...). |
| D19 | Industry-standard token signing (RS256 + JWKS). |
| D20 | Assistant permissions are configurable per assistant (list in section 3.4). |
| D21 | No assistants until the merchant is approved. A pending merchant can set up the store and draft products. |
| Earlier | Merchant/store naming in code; 1 merchant = 1 store; one role per account; super admin is unique and creates admins; admins can be banned by the super admin; banned admins cannot log in; a reason is always shown to the user; no customer approval; rejected users can re-apply; permanent ban is a separate state; multi-store cart with checkout split into one order per store and selectable items; owner-only: bank accounts, courier rates, store profile, creating assistants, store metrics; merchants can ban assistants and block customers from their store; COD can be disabled per store and per product; payment methods are bank transfer or COD; bank transfer is verified by merchant/assistant; all scores, timers and thresholds are super-admin configurable; mock SMS/email/image ports. |

---

## 1. Conventions

- **Naming:** `merchant` and `store` in all code, APIs, DB columns and docs. Never `seller` or `shop`.
- **Identifiers:** UUID primary keys stay internal (internal APIs and events only). Every customer-facing entity also gets a generated, unique, human-readable `publicId`, and all public APIs use it in paths and bodies.
  - Format: `PREFIX-YYMM-XXXXXX` where `XXXXXX` is 6 random Crockford base32 characters (no I, L, O, U). Example: `ORD-2610-7K2M9Q`.
  - Generated in the owning service, with a unique constraint and retry on collision. Random suffixes avoid leaking order volume.
  - Prefixes: `USR` user, `STR` store, `ITM` item (product), `VAR` variant, `ORD` order, `CHK` checkout group, `PAY` payment submission, `CMP` complaint, `REV` review, `FLG` flagged reference.
  - Stores also have a unique display `name` and a URL `slug` derived from it.
  - Master data uses admin-chosen short codes: banks (`BOC`, `COMB`), couriers (`DOMEX`, `KOOMBIYO`), categories (slug).
- **Time:** all timestamps are UTC `Instant`s in storage and JSON. Holiday handling and display use `Asia/Colombo`.
- **Money:** `numeric(12,2)` LKR, `BigDecimal`, never floats.
- **Pagination:** every list endpoint is paged (`page`, `size`, `sort`), with a whitelist of sortable fields and a max size of 50 (100 for admin lists). Keep the existing `PageResponse`.
- **Errors:** keep the existing JSON error shape (`timestamp, status, error, message, path, fieldErrors`). Add a stable `code` field (e.g. `STORE_NOT_ACCEPTING_ORDERS`).
- **Idempotency:** `POST /api/customer/checkout` and payment submission require an `Idempotency-Key` header; result stored per (user, key, request hash) for 24 h.
- **Schema:** every service manages its schema with Flyway SQL scripts (`src/main/resources/db/migration/V1__init.sql`, then `V2__...`), `spring.jpa.hibernate.ddl-auto=validate`. The scripts are maintained from now on; there are no data-preserving migrations for the current dev data (databases are reset once).
- **Optimistic locking:** `@Version` on orders, inventory, carts.
- **Audit:** every admin, super-admin, merchant-owner and assistant mutation writes an append-only `audit_log` row (who, role, storeId, action, target, before/after with sensitive fields masked, time, reason).

---

## 2. Architecture

```
            ┌──────────── api-gateway (8080) ────────────┐
   client → │ JWT check (JWKS), CORS, rate limit, routes │
            └───┬───────────┬───────────┬───────────┬────┘
                │           │           │           │
          user-service  store-service  product-service  order-service
            (8081)        (8084)         (8082)          (8083)
                └──── Kafka: user-events, store-events, product-events, order-events ────┘
              config-server (8888) · service-registry (8761)
```

| Service | Owns |
|---|---|
| **user-service** | Users, credentials, roles, statuses and ban reasons, NIC, phone, addresses, refresh tokens, approvals and bans, admin management, super-admin bootstrap, assistants and their permissions, token signing and JWKS, service tokens |
| **store-service** (new) | Store profile, bank master (banks), store bank accounts (encrypted), courier master, store couriers and shipping templates, platform settings, holiday calendar, merchant score and metrics, store reviews and replies, fee ledger and credits |
| **product-service** | Categories (admin), products (items), variants, discounts, inventory with reservations, public search, product reviews and replies, store visibility cache |
| **order-service** | Cart, checkout, orders and state machine, quotes, bank-transfer payments and flagged references, shipments, customer score and COD privilege, store-customer blocks, complaints, deadline scheduler |
| **api-gateway** | Single entry point, token pre-check, public-route list, CORS, rate limits, blocks `/internal/**` |

Rules:
- Each service owns its database and never reads another's tables.
- Synchronous calls (Feign + Resilience4j, as today) only where an answer is needed right now: price/stock quote and stock reservation (order to product), store/bank/courier data (order to store), address (order to user).
- Everything else is event-driven with a transactional outbox (section 9).
- The JWT `storeId` is assigned by user-service at merchant registration (the merchant's `storeId` is fixed from then on). store-service creates the store row under that id when the merchant saves the store profile.

---

## 3. Identity, roles and security

### 3.1 Roles and statuses

Roles (one per account): `ROLE_CUSTOMER`, `ROLE_MERCHANT`, `ROLE_ASSISTANT`, `ROLE_ADMIN`, `ROLE_SUPER_ADMIN`, plus `ROLE_SERVICE` for internal tokens only. Hierarchy: super admin includes admin powers.

User `status`:

| Status | Applies to | Login | Can act |
|---|---|---|---|
| `ACTIVE` | all | yes | yes |
| `PENDING_APPROVAL` | merchant | yes | set up store and draft products; nothing public; no assistants |
| `REJECTED` | merchant | yes | view reason, re-apply (max `merchant.max-application-attempts`, default 3) |
| `BAN_GRACE` | merchant | yes | as normal except no new orders (D4); not visible to the merchant |
| `BANNED` | all | customer: yes (view only); merchant: owner login for history only; assistant, admin: no | customer cannot buy; merchant cannot sell |

Assistant accounts also have an `assistantStatus`: `ACTIVE`, `BANNED` (by the merchant), `REMOVED`. Ban and removal need a reason and revoke tokens immediately.

Every status change stores `statusReason`, `statusChangedBy`, `statusChangedAt`. Login and `/me` responses always include `status`, the reason (once announced), and `mustChangePassword`.

### 3.2 Ban semantics

| Case | Behaviour |
|---|---|
| Customer banned | Login works. Checkout, quote confirmation and payment are refused with the reason. Unconfirmed orders are cancelled by the system with no score change. Confirmed orders continue. |
| Merchant banned (day 0) | Status becomes `BAN_GRACE` for `timers.ban-grace-days` (14). The store stops accepting orders and its listings leave search. Existing orders continue normally. The merchant and assistants keep working and are not told. Admins see the pending ban. |
| Merchant ban day 14 | A scheduler in user-service sets `BANNED`, reveals the reason to the merchant, blocks all assistants from logging in, and emits events. order-service flags remaining open orders for admin resolution. |
| Admin resolution | Admin endpoint per order: `FORCE_COMPLETE` or `CANCEL`, each with a reason and audit entry. |
| Admin banned | Only the super admin can do it. Login is refused with the reason. |
| Assistant banned or removed | By the merchant or an admin. Tokens revoked immediately. NIC release rule: section 13, point 5. |
| Unban | Restores `ACTIVE`, re-enables the store, emits events. |

### 3.3 Tokens

- **Access token:** JWT signed RS256. The private key lives only in user-service. The public key is served at `GET /.well-known/jwks.json`. All other services (and the gateway) verify with `spring-boot-starter-oauth2-resource-server` using the JWKS URI from Config Server, with issuer and audience checks, `exp`/`nbf` with 30 s skew. TTL 10 minutes (setting `auth.access-token-minutes`).
- **Claims:** `sub` (user UUID), `pid` (user publicId), `role`, `status`, `storeId` (merchant/assistant), `perms` (assistants only, list), `tv` (token version), `iss`, `aud`, `iat`, `exp`, `jti`.
- **Refresh token:** opaque random, stored hashed, rotated on every use with reuse detection (reuse revokes the whole token family), 14-day TTL, `POST /api/auth/refresh`, `POST /api/auth/logout`.
- **Revocation:** user-service increments `tokenVersion` on ban, unban, role or permission change, password change and assistant removal, then emits `UserSecurityChanged`. Every service keeps a small `user_security_state` cache (Caffeine + event-fed) and rejects tokens whose `tv` is older. On a cache miss it asks user-service (`/internal/users/{id}/security-state`). Worst-case staleness is bounded by the 10-minute TTL.
- **Services stop trusting `X-User-Id`/`X-User-Roles`.** The gateway forwards `Authorization`; each service authenticates the JWT itself and derives identity from it. The old `GatewayUserHeaderFilter` is removed.
- **Service-to-service:** `POST /internal/auth/service-token` in user-service (client id + secret from Config Server/secret store) issues a short-lived `ROLE_SERVICE` JWT. Internal endpoints live under `/internal/**`, require `ROLE_SERVICE`, are never routed by the gateway, and are also excluded from public OpenAPI. Calls made on behalf of a user (for example reading the caller's own address) forward the user's token.
- **Passwords:** `DelegatingPasswordEncoder` with Argon2id as default (bcrypt still verifies). Policy: min 12 characters, checked against a small deny list. Login throttling: lock an account/IP for `auth.lockout-minutes` (15) after `auth.max-failed-logins` (5) failures. Uniform error message for wrong email or password.

### 3.4 Permission matrix

Endpoint prefixes (enforced with `@PreAuthorize` and deny-by-default `authorizeHttpRequests`):

| Prefix | Who |
|---|---|
| `/api/public/**` | anyone (search, browse, reviews) |
| `/api/auth/**`, `/api/users/register/**` | anyone (login, refresh, register) |
| `/api/customer/**` | `ROLE_CUSTOMER` |
| `/api/merchant/**` | `ROLE_MERCHANT`, or `ROLE_ASSISTANT` holding the route's permission |
| `/api/merchant/owner/**` | `ROLE_MERCHANT` only (owner-only) |
| `/api/admin/**` | `ROLE_ADMIN`, `ROLE_SUPER_ADMIN` |
| `/api/super-admin/**` | `ROLE_SUPER_ADMIN` |
| `/internal/**` | `ROLE_SERVICE` |

Assistant permissions (configurable per assistant; merchant holds all): `ORDER_VIEW`, `ORDER_QUOTE` (quote, revise, reject), `PAYMENT_VERIFY`, `ORDER_SHIP`, `PRODUCT_EDIT`, `STOCK_EDIT`, `DISCOUNT_MANAGE`, `CUSTOMER_BLOCK`, `REVIEW_REPLY`.

Owner-only: bank accounts, courier and shipping templates, store profile, assistant management, store metrics/score.

Object-level rule (BOLA): every merchant-side query is scoped by `storeId` taken from the JWT, never from the request. Customer-side queries are scoped by the JWT subject. A foreign id returns `404`, not `403`.

### 3.5 Super-admin bootstrap

On startup, an idempotent `ApplicationRunner` in user-service (guarded by a PostgreSQL advisory lock, safe across replicas) creates the super admin if none exists. Email and initial password come from `SUPER_ADMIN_EMAIL` and `SUPER_ADMIN_INITIAL_PASSWORD` (Config Server secrets/env). If the database is empty and they are missing, startup fails. `mustChangePassword=true`. The password is never logged. A partial unique index `WHERE role='ROLE_SUPER_ADMIN'` enforces exactly one. The super admin creates admins through `POST /api/super-admin/admins` (email, names, NIC, phone, temporary password, forced change).

### 3.6 Registration and onboarding

- `POST /api/users/register/customer`: email, password, names, NIC, phone. Immediately `ACTIVE`. Email/SMS verification is a mock port that always succeeds.
- `POST /api/users/register/merchant`: same plus business name and mock document keys. Status `PENDING_APPROVAL`, `storeId` assigned.
- Merchant approval by admin: `POST /api/admin/merchants/{id}/approve` or `reject` (reason required). Re-application: `POST /api/merchant/application/resubmit`.
- Assistants: `POST /api/merchant/owner/assistants` (email, names, NIC, phone, permissions, temporary password, forced change). Allowed only when the merchant is `ACTIVE`.
- NIC validated as old (9 digits + V/X) or new (12 digits) format. Masked in responses except for admins and the user. Assistant NIC is unique among assistants via a partial unique index.

---

## 4. store-service

**Data:** `stores` (id = merchant's storeId, publicId, unique `name`, `slug`, description, contact, address, logo key [mock], `published`, `acceptingOrders`, ownerUserId, rating aggregates), `banks` (master), `store_bank_accounts` (bankCode, branch, accountName, account number AES-GCM encrypted, masked on read, active), `couriers` (master: code, name, tracking URL template, tracking number regex), `store_couriers` (enabled couriers), `shipping_templates` (store, courier, rule type flat / per-district / weight-band, amounts; used only as an estimate), `platform_settings` (+ history), `holidays` (date, name, type PUBLIC/POYA), `merchant_score_events`, `merchant_metrics`, `store_reviews` (+ `review_replies`, `review_eligibility`), `fee_ledger`, `merchant_credits`, `outbox`, `audit_log`.

**Behaviour:**
- Store is visible (`published && acceptingOrders`) only when the owner is `ACTIVE` and the profile is complete. It reacts to `UserStatusChanged`.
- Merchant score: consumes `order-events`, applies the configured deltas, and alerts admins when the score falls below `score.merchant.at-risk-threshold` (an admin list endpoint `GET /api/admin/merchants/at-risk`).
- Metrics (owner-only): score, acceptance rate, average response time, cancellation rate, late shipments, completed orders, revenue.
- Settings: super-admin CRUD with validation, audit log, 60 s cache plus `SettingsChanged` event for invalidation. Holidays: super-admin CRUD. Other services fetch both via internal endpoints.
- Fee ledger (D11): on `OrderCompleted`, record a `ORDER_FEE` row of `fees.per-order-lkr`; super admin grants credits (`POST /api/super-admin/merchants/{id}/credits`). No invoicing or enforcement (`fees.enforced=false`).
- Store reviews: eligible after `OrderCompleted`; one per order; rating 1-5 plus sanitized text; one merchant reply (permission `REVIEW_REPLY`); admin can hide with a reason.

---

## 5. product-service

**Data:** `categories` (admin-managed, flat with optional parent), `products` (id, publicId `ITM-`, storeId, name, description, categoryId, status `DRAFT|ACTIVE|ARCHIVED`, `codAllowed`, rating aggregates), `product_options` (for example Size, Colour), `variants` (publicId `VAR-`, sku unique per store, attribute map, `listPrice`, status), `inventory` (per variant: `quantityAvailable`, `reservedQuantity`, `@Version`), `stock_reservations` (orderRef, variant, qty, status `HELD|COMMITTED|RELEASED`, expiresAt), `discounts` (publicId, scope `VARIANT|PRODUCT`, type `PERCENT|FIXED`, value, startsAt, endsAt, active), `product_reviews` (+ replies, eligibility), `store_visibility` (event-fed: storeId to visible?), `outbox`, `audit_log`.

**Rules:**
- Every product has at least one variant (a simple product gets a default variant). The current product-level price, sku and inventory move onto variants.
- A product is searchable only when it is `ACTIVE`, the store is visible, and at least one variant is active. Merchant-side drafts are never public.
- Effective price = list price minus the best applicable active discount (a single discount wins, no stacking). Discount validity is evaluated at read time. Percent 1-90, fixed must leave price greater than 0.
- Inventory is changed only by (a) merchant stock edit (`STOCK_EDIT`, absolute set or delta with reason and audit) and (b) internal reservation calls. The existing user-callable `PATCH /inventory` is removed.
- Reservation API is all-or-nothing and idempotent per `orderRef`; `commit` turns held stock into a real decrement, `release` returns it. A safety-net scheduler releases holds past `expiresAt + 1 h` (order-service is authoritative).
- Public search: `q` (escaped ILIKE now, pluggable for a search engine later), category, store, price range, in-stock, rating, sort by relevance/price/newest/rating. Response DTOs expose only public fields (no reserved counts, no cost data).
- Image storage is a mock port (`ImageStoragePort`) that always succeeds and returns a placeholder URL; APIs already accept `imageKeys`.

---

## 6. order-service

### 6.1 Cart and checkout

- `carts` / `cart_items` (customer, variantId, storeId, qty). Items from several stores are allowed. Reads recompute live price, discount, stock and availability via product-service.
- `POST /api/customer/checkout` body: `{cartItemIds[], addressPublicId, payments: [{storeId, method: BANK_TRANSFER|COD}]}`. The customer picks which cart items to check out. The system groups them by store and creates one order per store under a shared `CHK-` group id. Purchased items leave the cart.
- Preconditions, all checked server-side, each with its own error code:
  - customer `ACTIVE` (not banned);
  - store visible and accepting orders;
  - customer not blocked by that store;
  - COD allowed: store COD enabled, every item's product `codAllowed`, customer COD privilege not suspended;
  - open unconfirmed orders below `orders.max-open-unconfirmed-per-customer` (10);
  - address snapshot is copied from user-service.
- At placement, stock is **held** (reservation with expiry) and prices are snapshotted per line: `listPrice`, `discountAmount`, `unitPrice`.

### 6.2 Order state machine

One order per store. Statuses:

`AWAITING_MERCHANT` -> `AWAITING_CUSTOMER_CONFIRMATION` -> (COD) `READY_TO_SHIP` | (bank) `AWAITING_PAYMENT` -> `PAYMENT_SUBMITTED` -> `READY_TO_SHIP` -> `SHIPPED` -> `COMPLETED`

Terminal: `CANCELLED_BY_CUSTOMER`, `REJECTED_BY_MERCHANT`, `EXPIRED_MERCHANT`, `DECLINED_BY_CUSTOMER`, `EXPIRED_CUSTOMER`, `EXPIRED_PAYMENT`, `DELIVERY_FAILED`, `CANCELLED_BY_SYSTEM`, `CLOSED_BY_ADMIN`.

| From | Action (actor) | To | Effects |
|---|---|---|---|
| `AWAITING_MERCHANT` | customer cancels | `CANCELLED_BY_CUSTOMER` | release stock, no penalty |
| `AWAITING_MERCHANT` | merchant/assistant rejects (out of stock, low score, COD refusals) | `REJECTED_BY_MERCHANT` | release stock, no penalty to either side |
| `AWAITING_MERCHANT` | 24 h passes | `EXPIRED_MERCHANT` | release stock, merchant score penalty |
| `AWAITING_MERCHANT` | merchant quotes: may only reduce or remove lines; adds courier charge (manual, mandatory), other charges (labelled), quote discount | `AWAITING_CUSTOMER_CONFIRMATION` | adjust hold, start 24 h timer; max `orders.max-quote-revisions` re-quotes while waiting (each resets the timer) |
| `AWAITING_CUSTOMER_CONFIRMATION` | customer confirms, COD | `READY_TO_SHIP` | commit stock |
| `AWAITING_CUSTOMER_CONFIRMATION` | customer confirms, bank | `AWAITING_PAYMENT` | show store bank accounts, 24 h timer |
| `AWAITING_CUSTOMER_CONFIRMATION` | customer declines | `DECLINED_BY_CUSTOMER` | release, customer -1 |
| `AWAITING_CUSTOMER_CONFIRMATION` | 24 h passes | `EXPIRED_CUSTOMER` | release, customer -1 |
| `AWAITING_PAYMENT` | customer submits reference, bank, deposited account | `PAYMENT_SUBMITTED` | duplicate check (6.3), start 24 h verification timer |
| `AWAITING_PAYMENT` | 24 h passes | `EXPIRED_PAYMENT` | release, customer -1 |
| `PAYMENT_SUBMITTED` | merchant/assistant verifies | `READY_TO_SHIP` | commit stock |
| `PAYMENT_SUBMITTED` | merchant/assistant rejects with reason | `AWAITING_PAYMENT` | customer can resubmit until the original payment deadline |
| `READY_TO_SHIP` | merchant/assistant ships (courier from store list, tracking number) | `SHIPPED` | tracking link built from courier template; starts the 7-day auto-complete |
| `SHIPPED` | customer marks received, or 7 days after shipped | `COMPLETED` | customer +1, merchant +1, review eligibility, fee ledger entry |
| `SHIPPED` | merchant marks COD refused / undeliverable | `DELIVERY_FAILED` | COD refusal count +1 (no score change); the 3rd refusal suspends COD for 3 months; the customer may object within 7 days and an admin decides (if upheld for the customer, the count is reversed and any suspension it caused is lifted) |
| any open | customer banned (unconfirmed only) | `CANCELLED_BY_SYSTEM` | release, no penalty |
| any open | admin resolve | `CLOSED_BY_ADMIN` or `COMPLETED` | reason and audit |

All transitions go through one `OrderStateMachine` class (actor, permission and current-state guards) and use optimistic locking so a timer and a user action cannot both win.

### 6.3 Payments (bank transfer)

- The customer supplies: `referenceNumber` (mandatory), deposit `bankCode`, and the depositing `accountNumber` (encrypted at rest), and picks which of the store's accounts was paid. Upload of a receipt is a mock-backed `attachmentKeys` list.
- The reference is normalised (trim, uppercase, remove spaces). The pair (normalised reference, destination bank) is checked across all stores. If it already exists, the submission is **rejected** with a generic message and a `flagged_payment_references` row (`FLG-` id, order, customer, store, reference, bank, original usage pointer, timestamp) is written; admins see a review queue (`GET /api/admin/flagged-references`) and can mark each reviewed or escalate to a customer/merchant ban.
- Merchant/assistant (`PAYMENT_VERIFY`) verifies or rejects. The system never handles money.

### 6.4 Timers and holidays

- Each order stores `deadlineAt` and `deadlineType`. A scheduler (every minute, ShedLock JDBC lock) picks due orders in batches with `FOR UPDATE SKIP LOCKED`, applies the transition, and emits events. Every handler is idempotent.
- `deadlineAt` = start + N real hours, where hours that fall on a holiday day (Colombo date in the holiday calendar) do not count. Implemented by one tested utility `DeadlineCalculator`; holidays come from store-service with a cache.
- Timers: merchant response, customer confirmation, payment submission, payment verification (all default 24 h); ship-by (default 72 h, section 13, point 3); auto-complete 7 days.

### 6.5 Customer score and COD privilege

- `customer_score_events` (type, delta, orderId, createdAt). Deltas and thresholds are settings.
- Default deltas: completed +1, declined or expired -1. A COD refusal does not change the score; it only counts toward the COD suspension.
- `customer_cod_state`: refusal count; when the 3rd refusal is recorded COD is suspended for `score.customer.cod-suspension-months` (3). Merchants see the customer's score and refusal count on the order.
- Merchant store block: `store_customer_blocks` (store, customer, reason, by whom), managed with `CUSTOMER_BLOCK`; checked at checkout.

### 6.6 Complaints

- `complaints` (`CMP-` id, order, customer, store, type `NOT_SHIPPED|WRONG_ITEM|NOT_AS_DESCRIBED|PAYMENT_DISPUTE|OTHER`, sanitized text, `attachmentKeys` (mock), status `OPEN|UNDER_REVIEW|UPHELD|DISMISSED`, admin notes).
- Customer can file from `READY_TO_SHIP` until `complaints.window-days` (30) after completion. A merchant can respond once.
- Admin decides. `UPHELD` adds a merchant score penalty and lets the admin ban the merchant (existing ban flow). No money is moved by the platform.

---

## 7. Platform settings (super-admin editable, audited)

| Key | Default |
|---|---|
| `timers.merchant-response-hours` | 24 |
| `timers.customer-confirmation-hours` | 24 |
| `timers.payment-submission-hours` | 24 |
| `timers.payment-verification-hours` | 24 |
| `timers.ship-by-hours` | 72 |
| `timers.auto-complete-days` | 7 |
| `timers.ban-grace-days` | 14 |
| `score.customer.completed` / `.declined` / `.expired` | +1 / -1 / -1 |
| `cod.objection-window-days` | 7 |
| `score.customer.cod-refusal-limit` / `.cod-suspension-months` | 3 / 3 |
| `score.merchant.completed` / `.response-timeout` / `.late-shipment` / `.late-verification` / `.complaint-upheld` | +1 / -2 / -1 / -1 / -3 |
| `score.merchant.at-risk-threshold` | -5 |
| `orders.max-open-unconfirmed-per-customer` | 10 |
| `orders.max-quote-revisions` | 2 |
| `complaints.window-days` | 30 |
| `merchant.max-application-attempts` | 3 |
| `auth.access-token-minutes` / `.refresh-days` / `.max-failed-logins` / `.lockout-minutes` | 10 / 14 / 5 / 15 |
| `fees.monthly-lkr` / `.per-order-lkr` / `.enforced` | 500 / 10 / false |

---

## 8. API catalogue (high level)

Gateway routes: `/api/auth/**`, `/api/users/**`, `/api/admin/users/**`, `/api/super-admin/admins/**` go to user-service. `/api/public/stores/**`, `/api/merchant/store/**`, `/api/merchant/owner/bank-accounts/**`, `/api/merchant/owner/couriers/**`, `/api/admin/banks/**`, `/api/super-admin/settings/**`, `/api/super-admin/holidays/**` go to store-service. `/api/public/products/**`, `/api/public/categories/**`, `/api/merchant/products/**`, `/api/merchant/discounts/**`, `/api/admin/categories/**` go to product-service. `/api/customer/cart/**`, `/api/customer/checkout`, `/api/customer/orders/**`, `/api/merchant/orders/**`, `/api/customer/complaints/**`, `/api/admin/complaints/**`, `/api/admin/flagged-references/**` go to order-service. `/internal/**` is never routed.

| Service | Key endpoints |
|---|---|
| user-service | `POST /api/auth/login, refresh, logout, change-password`; `POST /api/users/register/customer|merchant`; `GET/PUT /api/users/me`; `addresses` CRUD; `POST /api/merchant/application/resubmit`; owner: assistants create/list/permissions/ban/remove; admin: merchants pending/approve/reject/ban/unban, customers ban/unban, list/search users; super admin: admins create/ban/unban/list; `/.well-known/jwks.json`; internal: service-token, security-state, address lookup |
| store-service | public: store search/detail, store reviews; merchant: store profile, (owner) bank accounts, couriers, shipping templates, metrics, review replies; admin: banks master (super admin), courier master (super admin), at-risk merchants, review moderation; super admin: settings, holidays, credits; internal: store data for order, settings, holidays |
| product-service | public: product search/detail/reviews, categories; merchant: product/variant CRUD, stock edit, discounts, review replies; admin: categories CRUD, review moderation; internal: price-and-stock quote, reserve/commit/release |
| order-service | customer: cart CRUD, checkout, orders list/detail, cancel (before quote), confirm/decline quote, submit payment, mark received, complaints; merchant: orders list/detail, quote/revise, reject, verify/reject payment, ship, mark delivery failed, block/unblock customers, see customer score; admin: flagged references, complaints, resolve orders, customer score view |

---

## 9. Events and outbox

- Each publishing service writes events to an `outbox` table in the same transaction and a relay publishes to Kafka (at-least-once). Consumers are idempotent (processed-event table). This replaces today's fire-and-forget publisher.
- Topics (JSON with `eventType`, `eventId`, `occurredAt`, keyed by the aggregate's UUID):
  - `user-events`: `UserRegistered`, `UserStatusChanged`, `UserSecurityChanged`, `AssistantChanged`
  - `store-events`: `StoreVisibilityChanged`, `SettingsChanged`, `HolidaysChanged`
  - `product-events`: `ProductChanged`, `StockChanged` (for future use)
  - `order-events`: `OrderPlaced`, `OrderQuoted`, `OrderConfirmed`, `OrderPaymentSubmitted`, `OrderShipped`, `OrderCompleted`, `OrderCancelled` (with reason and terminal status), `OrderDeliveryFailed`, `PaymentReferenceFlagged`
- Consumers: store-service (order events for score/metrics/fees/review eligibility; user events for visibility), product-service (store visibility, order events for review eligibility), order-service (user events for security state and cancelling a banned customer's unconfirmed orders), user-service (none).

---

## 10. Mock ports

Interfaces with mock implementations that log and always succeed: `NotificationPort` (SMS, email, in-app record), `ImageStoragePort`, `VerificationPort` (email/phone verification). Stored in a `ports` package per service, selected by `@ConditionalOnProperty`, so real providers can be plugged in later without touching business code.

---

## 11. Security controls checklist

- Deny-by-default security in every service; JWT validated in every service; `/internal/**` restricted to `ROLE_SERVICE`; gateway never routes `/internal/**`.
- Ownership scoping in repositories (`findByIdAndStoreId`, `findByIdAndCustomerId`); foreign ids return 404.
- Request and response DTOs only; never bind entities; server-controlled fields (`status`, `storeId`, `role`, `score`, prices) are never accepted from clients.
- Bean Validation on every DTO with length and format limits; HTML sanitization (OWASP Java HTML Sanitizer) on all free text (descriptions, reviews, replies, complaints, notes); parameterised queries only; escape LIKE wildcards (as today).
- CORS: explicit origin allow-list per environment from Config Server, credentials only with listed origins, at the gateway only.
- Rate limiting at the gateway, per client (IP and user) instead of per route: strict on login/register/refresh, moderate on checkout and payment submission.
- Bank account numbers and depositor accounts encrypted (AES-GCM, key from secrets), masked in lists and logs; NIC masked for non-admins; no PII or secrets in logs.
- Bank account change writes audit entries. Security headers on all responses.
- Audit log on all privileged mutations.
- Idempotency keys on checkout and payment submission.
- Stock: row locks or conditional updates for reservations, `@Version` on inventory, and all-or-nothing idempotent reservations.
- Tests: a role-by-endpoint authorization matrix per service (every endpoint called with every role must give the expected 200/401/403/404), ownership tests, state-machine transition tests, deadline calculator tests including holidays, Testcontainers integration tests, WireMock for cross-service calls.

---

## 12. Delivery order (becomes the prompt plan)

1. **Shared contract file** (this document's sections 1, 3, 7, 9 condensed) attached to every prompt.
2. **config-server**: new config-repo files (store-service, JWKS/issuer/audience, CORS origins, service client secrets, Kafka), updated profiles.
3. **user-service**: roles, statuses, bootstrap, RS256 + JWKS, refresh tokens, assistants, approvals and bans, events, internal endpoints.
4. **store-service** (new repo scaffold copied from the same conventions), platform settings and master data first.
5. **product-service**: stores, variants, discounts, reservations, public search, reviews.
6. **order-service**: cart, checkout, state machine, scheduler, payments, scores, complaints.
7. **api-gateway**: JWKS validation, new routes, public-route list, per-client rate limits, CORS, block `/internal/**`.
8. **service-registry**: no change expected (confirmed when prompts are written).

Order 3 to 6 matters: user-service and store-service first (others depend on their tokens and settings).

---

## 13. Resolved points (final answers)

1. COD refusal: the customer may object within 7 days and an admin decides; merchant rejections based on the customer's score or COD history carry no penalty for either side.
2. A COD refusal has no score penalty. The 3rd refusal suspends COD for 3 months.
3. Ship-by default 72 h; a missed ship-by or payment-verification deadline keeps the order open, penalises the merchant's score and flags the order to admins.
4. Merchant ban is silent for 14 days: no new orders, listings leave public search, existing orders continue.
5. Assistant NIC is released when the merchant removes the assistant or bans them for under 30 days; an assistant banned by an admin for misconduct stays blocked permanently.
6. Flyway SQL scripts are maintained in every service (section 1).
7. Phone number is mandatory for customers and merchants.
8. A timer pauses on each holiday day (Colombo date).
