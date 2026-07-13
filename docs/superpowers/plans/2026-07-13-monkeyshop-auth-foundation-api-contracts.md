# MonkeyShop Auth Foundation And API Contracts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make registration, login, rate limiting, authorization, validation, pagination, and OpenAPI contracts trustworthy before the rebuilt UI depends on them.

**Architecture:** Keep authentication orchestration in `user/application`, policy and Redis counters behind domain ports, and HTTP semantics in `interfaces`. Configuration becomes typed Spring properties; the Vue app consumes machine-readable password and validation contracts rather than duplicating backend rules.

**Tech Stack:** Java 21, Spring Boot 3.3, Spring Security 6, Redis, Bucket4j-style policies, MockMvc, JUnit 5, Vue 3.5, TypeScript, Vitest.

## Global Constraints

- Preserve the existing modular-monolith package boundaries and all uncommitted user changes.
- Develop every behavior test-first: one failing focused test, minimal implementation, focused regression, then commit.
- Registration limits are `dev: 120/hour/IP`, `prod edge: 20/15m/IP`, and `prod identity: 5/hour` for each username hash and phone hash.
- Login limits are `30/5m/IP`, `10/5m/username+IP`, captcha after 3 pair failures, and a 10-minute pair lock after 10 failures.
- A successful login clears pair failure state and decays the shared-IP counter.
- Controls use 6px radius; cards, drawers, and dialogs use 8px radius; no gradients, decorative orbs, bokeh, or nested cards.
- Malformed JSON and type conversion failures return HTTP 400; semantic validation failures return HTTP 422.
- Every 429 response carries an accurate integer `Retry-After` header and a machine-readable retry timestamp.

---

## File Map

- `src/main/java/com/example/monkey/user/interfaces/AuthController.java`: public auth HTTP contract.
- `src/main/java/com/example/monkey/user/application/RegistrationApplicationService.java`: registration orchestration after request parsing.
- `src/main/java/com/example/monkey/user/application/LoginApplicationService.java`: progressive login flow.
- `src/main/java/com/example/monkey/user/infrastructure/LoginAttemptService.java`: Redis-backed pair/IP counters and lock expiry.
- `src/main/java/com/example/monkey/shared/infrastructure/security/ApiRateLimitService.java`: configurable endpoint quotas.
- `src/main/java/com/example/monkey/shared/interfaces/web/GlobalExceptionHandler.java`: ProblemDetail status and field errors.
- `src/main/java/com/example/monkey/shared/infrastructure/config/SecurityConfig.java`: canonical request authorization matrix; retain the existing SPA CSRF handler.
- `frontend/src/api/auth.ts`, `frontend/src/types.ts`: generated-shaped auth contracts.
- `frontend/src/views/LoginView.vue`: registration/login/reset state machine and inline errors.

### Task 1: Publish The Password Policy Contract

**Files:**
- Create: `src/main/java/com/example/monkey/user/application/dto/PasswordPolicyResponseDto.java`
- Modify: `src/main/java/com/example/monkey/user/infrastructure/PasswordPolicy.java`
- Modify: `src/main/java/com/example/monkey/user/interfaces/AuthController.java`
- Modify: `src/main/java/com/example/monkey/shared/infrastructure/config/SecurityConfig.java`
- Test: `src/test/java/com/example/monkey/user/infrastructure/PasswordPolicyTest.java`
- Test: `src/test/java/com/example/monkey/user/interfaces/AuthControllerTest.java`

**Interfaces:**
- Consumes: `PasswordPolicy.validate(String password, String username)`.
- Produces: `GET /api/v1/auth/password-policy -> Result<PasswordPolicyResponseDto>` where the DTO is `record PasswordPolicyResponseDto(int minLength, boolean requireUppercase, boolean requireLowercase, boolean requireDigit, boolean requireSpecial, boolean forbidWhitespace)`.

- [ ] **Step 1: Write failing policy metadata tests**

```java
assertThat(passwordPolicy.metadata()).isEqualTo(
        new PasswordPolicyResponseDto(10, true, true, true, true, true));

mockMvc.perform(get("/api/v1/auth/password-policy"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.minLength").value(10))
        .andExpect(jsonPath("$.data.requireSpecial").value(true));
```

- [ ] **Step 2: Run the focused tests and confirm RED**

Run: `& 'D:\APP\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd' -B '-Ddependency-check.skip=true' '-Dtest=PasswordPolicyTest,AuthControllerTest' test`

Expected: compilation fails because `metadata()` and `PasswordPolicyResponseDto` do not exist.

- [ ] **Step 3: Add the immutable metadata method and public endpoint**

```java
public PasswordPolicyResponseDto metadata() {
    return new PasswordPolicyResponseDto(10, true, true, true, true, true);
}

@GetMapping("/password-policy")
public Result<PasswordPolicyResponseDto> passwordPolicy() {
    return Result.success(passwordPolicy.metadata());
}
```

Add `/api/v1/auth/password-policy` and its legacy alias to the existing public auth matcher without changing CSRF behavior for mutations.

- [ ] **Step 4: Run focused tests and commit**

Run the command from Step 2. Expected: both classes pass with zero failures.

```powershell
git add src/main/java/com/example/monkey/user/application/dto/PasswordPolicyResponseDto.java src/main/java/com/example/monkey/user/infrastructure/PasswordPolicy.java src/main/java/com/example/monkey/user/interfaces/AuthController.java src/main/java/com/example/monkey/shared/infrastructure/config/SecurityConfig.java src/test/java/com/example/monkey/user/infrastructure/PasswordPolicyTest.java src/test/java/com/example/monkey/user/interfaces/AuthControllerTest.java
git commit -m "feat(auth): publish password policy metadata"
```

### Task 2: Return Structured 400 And 422 Problems

**Files:**
- Create: `src/main/java/com/example/monkey/shared/interfaces/web/FieldViolation.java`
- Modify: `src/main/java/com/example/monkey/shared/interfaces/web/GlobalExceptionHandler.java`
- Modify: `src/main/java/com/example/monkey/shared/interfaces/web/ProblemDetails.java`
- Test: `src/test/java/com/example/monkey/shared/interfaces/web/GlobalExceptionHandlerTest.java`
- Test: `src/test/java/com/example/monkey/user/interfaces/AuthControllerTest.java`

**Interfaces:**
- Produces: ProblemDetail extension `fieldErrors: [{field:string, code:string, message:string}]` for bean validation.
- Produces: `application/problem+json`, HTTP 400, `code=REQUEST_MALFORMED` for malformed JSON/type conversion.
- Produces: HTTP 422, `code=VALIDATION_FAILED` for parsed but semantically invalid data.

- [ ] **Step 1: Add RED MockMvc assertions**

```java
mockMvc.perform(post("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("REQUEST_MALFORMED"));

mockMvc.perform(post("/api/v1/auth/register")
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .param("username", "x"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.fieldErrors[0].field").exists());
```

- [ ] **Step 2: Run RED test**

Run: `& 'D:\APP\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd' -B '-Ddependency-check.skip=true' '-Dtest=GlobalExceptionHandlerTest,AuthControllerTest' test`

Expected: malformed input is currently 422 and field errors are absent or unstructured.

- [ ] **Step 3: Implement deterministic mappings**

```java
public record FieldViolation(String field, String code, String message) {}

@ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
ResponseEntity<ProblemDetail> malformedRequest(Exception exception, HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "REQUEST_MALFORMED", "请求格式无法解析", request);
}
```

Sort field violations by field name, use Bean Validation constraint names as `code`, and never expose exception class names or raw stack messages.

- [ ] **Step 4: Run tests and commit**

Expected: focused tests pass; `Content-Type` is `application/problem+json`.

```powershell
git add src/main/java/com/example/monkey/shared/interfaces/web/FieldViolation.java src/main/java/com/example/monkey/shared/interfaces/web/GlobalExceptionHandler.java src/main/java/com/example/monkey/shared/interfaces/web/ProblemDetails.java src/test/java/com/example/monkey/shared/interfaces/web/GlobalExceptionHandlerTest.java src/test/java/com/example/monkey/user/interfaces/AuthControllerTest.java
git commit -m "fix(api): separate malformed and validation errors"
```

### Task 3: Make Registration Quotas Configurable And Identity-Aware

**Files:**
- Create: `src/main/java/com/example/monkey/shared/infrastructure/security/RateLimitProperties.java`
- Modify: `src/main/java/com/example/monkey/shared/domain/security/RateLimitPolicy.java`
- Modify: `src/main/java/com/example/monkey/shared/infrastructure/security/ApiRateLimitService.java`
- Modify: `src/main/java/com/example/monkey/shared/interfaces/security/ApiRateLimitFilter.java`
- Modify: `src/main/java/com/example/monkey/user/application/RegistrationApplicationService.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-dev.yml`
- Modify: `src/main/resources/application-prod.yml`
- Test: `src/test/java/com/example/monkey/shared/infrastructure/security/ApiRateLimitServiceTest.java`
- Test: `src/test/java/com/example/monkey/shared/interfaces/security/ApiRateLimitFilterTest.java`
- Test: `src/test/java/com/example/monkey/user/application/RegistrationApplicationServiceTest.java`

**Interfaces:**
- Consumes: `RateLimitDecision consume(RateLimitPolicy policy, String clientIp, String userKey)`.
- Produces: typed properties `monkeyshop.rate-limit.register.edge.capacity`, `.window`, `.identity-capacity`, `.identity-window`.
- Produces: HMAC-SHA256 identity keys `register:username:<hash>` and `register:phone:<hash>`; raw PII never appears in Redis keys.

- [ ] **Step 1: Write RED quota tests**

```java
for (int i = 0; i < 120; i++) {
    assertThat(service.consume(REGISTER, "127.0.0.1", "anonymous").allowed()).isTrue();
}
assertThat(service.consume(REGISTER, "127.0.0.1", "anonymous").allowed()).isFalse();

verify(identityLimiter).consume("register:username:" + expectedUsernameHash, 5, Duration.ofHours(1));
verify(identityLimiter).consume("register:phone:" + expectedPhoneHash, 5, Duration.ofHours(1));
```

- [ ] **Step 2: Run RED tests**

Run: `& 'D:\APP\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd' -B '-Ddependency-check.skip=true' '-Dtest=ApiRateLimitServiceTest,ApiRateLimitFilterTest,RegistrationApplicationServiceTest' test`

Expected: existing enum limits do not read profile properties and registration has no two identity counters.

- [ ] **Step 3: Implement typed properties and consumption order**

```java
@ConfigurationProperties(prefix = "monkeyshop.rate-limit")
public record RateLimitProperties(Register register, Login login) {
    public record Register(int edgeCapacity, Duration edgeWindow, int identityCapacity, Duration identityWindow) {}
    public record Login(int ipCapacity, Duration ipWindow, int pairCapacity, Duration pairWindow) {}
}
```

Parse and validate the registration DTO first; consume edge quota at the filter and identity quotas inside `RegistrationApplicationService` immediately before uniqueness checks and persistence. Add `Retry-After` as `ceil((resetAt-now)/1s)` with a minimum of 1.

- [ ] **Step 4: Configure exact profile values**

```yaml
monkeyshop:
  rate-limit:
    register:
      edge-capacity: 120
      edge-window: 1h
      identity-capacity: 120
      identity-window: 1h
```

Use `20/15m` and `5/1h` in `application-prod.yml`; staging uses the production values.

- [ ] **Step 5: Run tests and commit**

Expected: all three focused test classes pass, including exact Retry-After assertions.

```powershell
git add src/main/java/com/example/monkey/shared/infrastructure/security/RateLimitProperties.java src/main/java/com/example/monkey/shared/domain/security/RateLimitPolicy.java src/main/java/com/example/monkey/shared/infrastructure/security/ApiRateLimitService.java src/main/java/com/example/monkey/shared/interfaces/security/ApiRateLimitFilter.java src/main/java/com/example/monkey/user/application/RegistrationApplicationService.java src/main/resources/application.yml src/main/resources/application-dev.yml src/main/resources/application-staging.yml src/main/resources/application-prod.yml src/test/java/com/example/monkey/shared/infrastructure/security/ApiRateLimitServiceTest.java src/test/java/com/example/monkey/shared/interfaces/security/ApiRateLimitFilterTest.java src/test/java/com/example/monkey/user/application/RegistrationApplicationServiceTest.java
git commit -m "fix(auth): configure progressive registration quotas"
```

### Task 4: Implement Progressive Login Protection

**Files:**
- Modify: `src/main/java/com/example/monkey/user/domain/LoginAttemptPolicy.java`
- Modify: `src/main/java/com/example/monkey/user/infrastructure/LoginAttemptService.java`
- Modify: `src/main/java/com/example/monkey/user/application/LoginApplicationService.java`
- Modify: `src/main/java/com/example/monkey/user/interfaces/AuthController.java`
- Test: `src/test/java/com/example/monkey/user/infrastructure/LoginAttemptServiceTest.java`
- Test: `src/test/java/com/example/monkey/user/application/LoginApplicationServiceTest.java`
- Test: `src/test/java/com/example/monkey/user/interfaces/AuthControllerTest.java`

**Interfaces:**
- Produces: `LoginAttemptState evaluate(String username, String clientIp)` with `captchaRequired`, `locked`, and `retryAfterSeconds`.
- Produces: `recordFailure`, `recordSuccess`, and `decayIpCounter` using username+IP pair keys.

- [ ] **Step 1: Write the RED state-transition tests**

```java
recordFailure("alice", "10.0.0.8");
recordFailure("alice", "10.0.0.8");
assertThat(evaluate("alice", "10.0.0.8").captchaRequired()).isFalse();
recordFailure("alice", "10.0.0.8");
assertThat(evaluate("alice", "10.0.0.8").captchaRequired()).isTrue();

IntStream.range(3, 10).forEach(i -> recordFailure("alice", "10.0.0.8"));
assertThat(evaluate("alice", "10.0.0.8").locked()).isTrue();
assertThat(evaluate("bob", "10.0.0.8").locked()).isFalse();
```

- [ ] **Step 2: Run RED tests**

Run: `& 'D:\APP\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd' -B '-Ddependency-check.skip=true' '-Dtest=LoginAttemptServiceTest,LoginApplicationServiceTest,AuthControllerTest' test`

Expected: old thresholds or username-only locks violate at least one assertion.

- [ ] **Step 3: Implement pair state and success decay**

```java
public record LoginAttemptState(boolean captchaRequired, boolean locked, long retryAfterSeconds) {}

public void recordSuccess(String username, String clientIp) {
    redis.delete(pairFailureKey(username, clientIp));
    redis.delete(pairLockKey(username, clientIp));
    decrementIfPositive(ipCounterKey(clientIp));
}
```

Store normalized username hashes, not usernames. Use one atomic Redis script for increment + TTL and one for decrement-if-positive. Return 429 only for rate or lock state; bad credentials remain uniform 401.

- [ ] **Step 4: Run tests and commit**

```powershell
git add src/main/java/com/example/monkey/user/domain/LoginAttemptPolicy.java src/main/java/com/example/monkey/user/infrastructure/LoginAttemptService.java src/main/java/com/example/monkey/user/application/LoginApplicationService.java src/main/java/com/example/monkey/user/interfaces/AuthController.java src/test/java/com/example/monkey/user/infrastructure/LoginAttemptServiceTest.java src/test/java/com/example/monkey/user/application/LoginApplicationServiceTest.java src/test/java/com/example/monkey/user/interfaces/AuthControllerTest.java
git commit -m "fix(auth): apply pair-scoped progressive login protection"
```

### Task 5: Trust Forwarded IPs Only From Configured Proxies

**Files:**
- Modify: `src/main/java/com/example/monkey/shared/interfaces/web/ClientIps.java`
- Create: `src/main/java/com/example/monkey/shared/infrastructure/config/TrustedProxyProperties.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-prod.yml`
- Test: `src/test/java/com/example/monkey/shared/interfaces/web/ClientIpsTest.java`

**Interfaces:**
- Produces: `String resolve(HttpServletRequest request)` that reads forwarding headers only when `remoteAddr` matches a configured CIDR.

- [ ] **Step 1: Add RED spoofing tests**

```java
request.setRemoteAddr("203.0.113.4");
request.addHeader("X-Forwarded-For", "1.2.3.4");
assertThat(clientIps.resolve(request)).isEqualTo("203.0.113.4");

request.setRemoteAddr("10.0.0.10");
request.addHeader("X-Forwarded-For", "1.2.3.4, 10.0.0.9");
assertThat(clientIps.resolve(request)).isEqualTo("1.2.3.4");
```

- [ ] **Step 2: Run RED, implement CIDR matching, and run GREEN**

Run: `& 'D:\APP\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd' -B '-Ddependency-check.skip=true' '-Dtest=ClientIpsTest' test`

Expected before implementation: spoofed public header is trusted. Expected after implementation: all tests pass.

- [ ] **Step 3: Commit**

```powershell
git add src/main/java/com/example/monkey/shared/interfaces/web/ClientIps.java src/main/java/com/example/monkey/shared/infrastructure/config/TrustedProxyProperties.java src/main/resources/application.yml src/main/resources/application-prod.yml src/test/java/com/example/monkey/shared/interfaces/web/ClientIpsTest.java
git commit -m "fix(security): restrict forwarded IP trust"
```

### Task 6: Close The Security Filter Matrix

**Files:**
- Modify: `src/main/java/com/example/monkey/shared/infrastructure/config/SecurityConfig.java`
- Modify: `src/main/java/com/example/monkey/inventory/interfaces/InventoryController.java`
- Modify: `src/main/java/com/example/monkey/marketing/interfaces/MarketingController.java`
- Create: `src/test/java/com/example/monkey/security/SecurityFilterChainMatrixTest.java`
- Modify: `src/test/java/com/example/monkey/security/RbacPermissionMatrixTest.java`

**Interfaces:**
- Produces: explicit filter rules for Catalog reads, consumer marketing return, inventory release/compensation, and every canonical `/api/v1/**` controller route.
- Preserves: machine callbacks/webhooks are authenticated by signature/source controls and never exposed as user actions.

- [ ] **Step 1: Add a parameterized RED matrix**

```java
@ParameterizedTest
@MethodSource("requests")
void enforcesExpectedDecision(String method, String path, Authentication auth, int expectedStatus) {
    perform(method, path, auth).andExpect(status().is(expectedStatus));
}

static Stream<Arguments> requests() {
    return Stream.of(
        arguments("GET", "/api/v1/catalog/categories", anonymous(), 200),
        arguments("POST", "/api/v1/catalog/spus", user("USER"), 403),
        arguments("POST", "/api/v1/catalog/spus", permission("PRODUCT_MANAGE"), 200),
        arguments("POST", "/api/v1/marketing/coupons/return", user("USER"), 200),
        arguments("POST", "/api/v1/inventory/reservations/1/release", permission("ORDER_MANAGE"), 200));
}
```

- [ ] **Step 2: Run RED test**

Run: `& 'D:\APP\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd' -B '-Ddependency-check.skip=true' '-Dtest=SecurityFilterChainMatrixTest,RbacPermissionMatrixTest' test`

Expected: Catalog reads currently fall into `/api/** denyAll`; at least the release/return rules disagree with controller annotations.

- [ ] **Step 3: Align filter and method-level permissions**

Keep `ORDER_MANAGE` as the sole permission for explicit inventory compensation/release endpoints. Allow the authenticated owner-facing coupon return endpoint and enforce ownership in `MarketingApplicationService`. Enumerate Catalog public reads and `PRODUCT_MANAGE` writes before the final deny rule.

- [ ] **Step 4: Run matrix plus CSRF regression and commit**

Run: `& 'D:\APP\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd' -B '-Ddependency-check.skip=true' '-Dtest=SecurityFilterChainMatrixTest,RbacPermissionMatrixTest,SpaCsrfIntegrationTest,ControllerAuthorizationDeclarationTest' test`

Expected: all classes pass; mutations without CSRF still fail according to the existing SPA contract.

```powershell
git add src/main/java/com/example/monkey/shared/infrastructure/config/SecurityConfig.java src/main/java/com/example/monkey/inventory/interfaces/InventoryController.java src/main/java/com/example/monkey/marketing/interfaces/MarketingController.java src/test/java/com/example/monkey/security/SecurityFilterChainMatrixTest.java src/test/java/com/example/monkey/security/RbacPermissionMatrixTest.java
git commit -m "fix(security): complete API authorization matrix"
```

### Task 7: Stabilize Pagination, Idempotency, And OpenAPI

**Files:**
- Modify: `src/main/java/com/example/monkey/shared/infrastructure/config/OpenApiConfig.java`
- Modify: `src/main/java/com/example/monkey/order/interfaces/OrderController.java`
- Modify: `src/main/java/com/example/monkey/payment/interfaces/PaymentController.java`
- Modify: `src/main/java/com/example/monkey/payment/interfaces/PaymentAdminController.java`
- Modify: `src/main/java/com/example/monkey/cart/interfaces/CartController.java`
- Modify: `src/main/java/com/example/monkey/logistics/interfaces/LogisticsController.java`
- Modify: `src/main/java/com/example/monkey/membership/interfaces/MembershipController.java`
- Modify: `src/main/java/com/example/monkey/product/interfaces/MonkeyController.java`
- Modify: `src/main/java/com/example/monkey/user/interfaces/AddressController.java`
- Test: `src/test/java/com/example/monkey/contract/ApiVersioningContractTest.java`
- Test: `src/test/java/com/example/monkey/order/interfaces/OrderControllerApiContractTest.java`
- Create: `src/test/java/com/example/monkey/contract/OpenApiRuntimeContractTest.java`

**Interfaces:**
- Produces: OpenAPI server `/` with canonical paths already containing `/api/v1` exactly once.
- Produces: all mutating idempotent operations require `@RequestHeader(name = "Idempotency-Key", required = true)`.
- Produces: each collection route always returns `PageResponseDto<T>` with default size 20 and maximum size 100.

- [ ] **Step 1: Write RED runtime OpenAPI assertions**

```java
JsonNode document = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
assertThat(document.at("/servers/0/url").asText()).isEqualTo("/");
assertThat(document.at("/paths/~1api~1v1~1orders/post/parameters/0/required").asBoolean()).isTrue();
```

- [ ] **Step 2: Run RED contracts**

Run: `& 'D:\APP\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd' -B '-Ddependency-check.skip=true' '-Dtest=ApiVersioningContractTest,OrderControllerApiContractTest,OpenApiRuntimeContractTest' test`

Expected: double-prefix server/path or optional idempotency declarations fail.

- [ ] **Step 3: Normalize controller contracts**

```java
@RequestHeader(name = "Idempotency-Key") @NotBlank String idempotencyKey
```

Reject blank keys with 422. Replace list-or-page branching on a single route with one stable `PageResponseDto<T>` schema and clamp size through `JpaPageRequests.of(page, size, 100, sort)`.

- [ ] **Step 4: Run contracts and commit**

```powershell
git add src/main/java/com/example/monkey/shared/infrastructure/config/OpenApiConfig.java src/main/java/com/example/monkey/order/interfaces/OrderController.java src/main/java/com/example/monkey/payment/interfaces/PaymentController.java src/main/java/com/example/monkey/payment/interfaces/PaymentAdminController.java src/main/java/com/example/monkey/cart/interfaces/CartController.java src/main/java/com/example/monkey/logistics/interfaces/LogisticsController.java src/main/java/com/example/monkey/membership/interfaces/MembershipController.java src/main/java/com/example/monkey/product/interfaces/MonkeyController.java src/main/java/com/example/monkey/user/interfaces/AddressController.java src/test/java/com/example/monkey/contract/ApiVersioningContractTest.java src/test/java/com/example/monkey/order/interfaces/OrderControllerApiContractTest.java src/test/java/com/example/monkey/contract/OpenApiRuntimeContractTest.java
git commit -m "fix(api): stabilize generated contracts"
```

Before staging, inspect `git diff --name-only` and stage only controller files actually changed by this task; do not stage unrelated dirty files under the broad Java directory.

### Task 8: Bind The Vue Auth Client To Backend Contracts

**Files:**
- Modify: `frontend/src/types.ts`
- Modify: `frontend/src/api/auth.ts`
- Create: `frontend/src/composables/usePasswordPolicy.ts`
- Create: `frontend/src/composables/useRetryCountdown.ts`
- Test: `frontend/src/composables/usePasswordPolicy.test.ts`
- Test: `frontend/src/composables/useRetryCountdown.test.ts`
- Modify: `frontend/src/api/http.test.ts`

**Interfaces:**
- Consumes: `GET /api/v1/auth/password-policy` and ProblemDetail `fieldErrors`/`retryAfterSeconds`.
- Produces: `PasswordPolicy`, `FieldViolation`, `ApiProblem`, `usePasswordPolicy()`, and `useRetryCountdown()`.

- [ ] **Step 1: Write RED Vitest cases**

```ts
expect(evaluatePassword('Abcdef1!xx', policy)).toEqual({
  minLength: true, uppercase: true, lowercase: true, digit: true, special: true, noWhitespace: true,
})
expect(parseRetryAfter({ headers: { 'retry-after': '9' } }, 1_000)).toBe(10_000)
```

- [ ] **Step 2: Run RED tests**

Run: `npm run test:unit -- src/composables/usePasswordPolicy.test.ts src/composables/useRetryCountdown.test.ts src/api/http.test.ts`

Working directory: `frontend`. Expected: missing composables/types fail compilation.

- [ ] **Step 3: Implement typed parsing**

```ts
export interface PasswordPolicy {
  minLength: number
  requireUppercase: boolean
  requireLowercase: boolean
  requireDigit: boolean
  requireSpecial: boolean
  forbidWhitespace: boolean
}

export interface FieldViolation { field: string; code: string; message: string }
```

Use `Retry-After` header first, then the ProblemDetail retry field; return an absolute epoch so the countdown survives component rerenders.

- [ ] **Step 4: Run unit, typecheck, and commit**

Run: `npm run test:unit -- src/composables/usePasswordPolicy.test.ts src/composables/useRetryCountdown.test.ts src/api/http.test.ts`

Run: `npm run typecheck`

Expected: both commands exit 0.

```powershell
git add frontend/src/types.ts frontend/src/api/auth.ts frontend/src/composables/usePasswordPolicy.ts frontend/src/composables/useRetryCountdown.ts frontend/src/composables/usePasswordPolicy.test.ts frontend/src/composables/useRetryCountdown.test.ts frontend/src/api/http.test.ts
git commit -m "feat(frontend): consume auth policy contracts"
```

### Task 9: Make Captcha, Password Reset, And JWT Failure Handling Abuse-Safe

**Files:**
- Modify: `src/main/java/com/example/monkey/user/infrastructure/RedisCaptchaChallengeStore.java`
- Modify: `src/main/java/com/example/monkey/user/infrastructure/PasswordResetOtpService.java`
- Modify: `src/main/java/com/example/monkey/user/infrastructure/JwtTokenService.java`
- Test: `src/test/java/com/example/monkey/user/infrastructure/RedisCaptchaChallengeStoreTest.java`
- Test: `src/test/java/com/example/monkey/user/infrastructure/PasswordResetOtpServiceTest.java`
- Test: `src/test/java/com/example/monkey/user/infrastructure/JwtTokenServiceTest.java`

**Interfaces:**
- Produces: atomic one-time captcha consume through Redis `GETDEL` or an equivalent Lua script.
- Produces: password-reset edge quota for every request, but phone identity quota only when the supplied identity matches; HTTP response remains uniform.
- Produces: JWT parse failure metric `auth.jwt.parse.failure{reason}` and safe diagnostics that never include raw tokens or claims.

- [ ] **Step 1: Write RED atomicity and quota tests**

```java
when(redisTemplate.execute(any(DefaultRedisScript.class), eq(List.of("captcha:id-1"))))
        .thenReturn("ABCD");
assertThat(store.consume("id-1")).contains("ABCD");
verify(redisTemplate, never()).delete(anyString());

IntStream.range(0, 5).forEach(i -> service.issueResetChallenge("missing", "13800000000", null, false));
service.issueResetChallenge("alice", "13800000000", null, true);
verify(deliveryService).sendSmsOtp(eq("13800000000"), anyString());
```

- [ ] **Step 2: Write RED JWT observability test**

```java
assertThat(tokenService.parseAccessToken("not-a-jwt")).isEmpty();
assertThat(meterRegistry.get("auth.jwt.parse.failure").tag("reason", "malformed").counter().count())
        .isEqualTo(1.0d);
assertThat(capturedLogs).noneMatch(message -> message.contains("not-a-jwt"));
```

- [ ] **Step 3: Run RED tests**

Run: `& 'D:\APP\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd' -B '-Ddependency-check.skip=true' '-Dtest=RedisCaptchaChallengeStoreTest,PasswordResetOtpServiceTest,JwtTokenServiceTest' test`

Expected: captcha uses separate GET/DELETE, nonmatching reset requests consume phone quota, and parse failures have no metric.

- [ ] **Step 4: Implement atomic consume and classified failures**

```java
private static final DefaultRedisScript<String> GET_AND_DELETE =
        new DefaultRedisScript<>("local v=redis.call('GET',KEYS[1]); redis.call('DEL',KEYS[1]); return v", String.class);

public Optional<String> consume(String challengeId) {
    return Optional.ofNullable(redisTemplate.execute(GET_AND_DELETE, List.of(redisKey(challengeId))));
}
```

Move `enforcePhoneLimit(normalizedPhone, now)` inside the `targetMatches` branch while retaining a separate IP/edge limiter before identity lookup. In `JwtTokenService`, classify malformed, expired, invalid-signature, revoked, and unexpected failures; increment a bounded-tag counter. Log expected client failures at debug and unexpected internal failures at warn with exception class only.

- [ ] **Step 5: Run GREEN and commit**

Run the command from Step 3. Expected: all three test classes pass with no token/phone/user value in captured logs.

```powershell
git add src/main/java/com/example/monkey/user/infrastructure/RedisCaptchaChallengeStore.java src/main/java/com/example/monkey/user/infrastructure/PasswordResetOtpService.java src/main/java/com/example/monkey/user/infrastructure/JwtTokenService.java src/test/java/com/example/monkey/user/infrastructure/RedisCaptchaChallengeStoreTest.java src/test/java/com/example/monkey/user/infrastructure/PasswordResetOtpServiceTest.java src/test/java/com/example/monkey/user/infrastructure/JwtTokenServiceTest.java
git commit -m "fix(auth): harden one-time challenges and token diagnostics"
```

## Plan Acceptance

- `PasswordPolicyTest`, `AuthControllerTest`, `LoginAttemptServiceTest`, `SecurityFilterChainMatrixTest`, `OpenApiRuntimeContractTest`, and `SpaCsrfIntegrationTest` pass together.
- Dev registration accepts 120 parsed requests per hour per IP; production values are profile-configured and covered by property tests.
- Two ordinary failures never lock a login or registration form.
- Spoofed `X-Forwarded-For` from an untrusted peer has no effect.
- Captcha is consumed atomically, password-reset identity quota cannot be exhausted for a nonmatching account, and JWT failures are observable without leaking token data.
- Catalog reads work anonymously; Catalog writes require `PRODUCT_MANAGE`.
- Malformed JSON is 400, semantic validation is 422, and 429 includes accurate retry metadata.
- Frontend auth contract unit tests and `npm run typecheck` pass.
