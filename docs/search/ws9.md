# WS9 Search and Recommendation

## Goal

Turn product discovery into an industrial search and recommendation slice comparable to Taobao search, JD recommendations, and Pinduoduo feed ranking. The slice must reuse the existing catalog, membership browsing history, order history, Redis, PII crypto, rate limiting, honeypot blocking, OpenTelemetry, audit log, and business metrics instead of introducing a decorative standalone search demo.

## Scope

- Product search by keyword, category, attribute filter, and sort mode.
- Search suggestions sourced from Redis cache, recent user history, and hot keywords.
- Real-time hot keyword Top 10 with a five-minute scheduled refresh snapshot.
- Personalized recommendations from WS8 browse history, order purchase history, and encrypted user search profile tags.
- Search conversion events recorded through `BusinessMetricsService`.
- Search profile PII stored with Tink encryption and HMAC blind index.
- `/api/search/internal/hot` is a honeypot path and blocks the probing client for the shared WAF duration.

## Invariants

- `search_history` writes use Snowflake IDs and keep keyword, filter, result count, click, and conversion evidence.
- `user_search_profile.encrypted_interest_profile` stores the sensitive profile summary, while `interest_profile_hmac` is the blind index.
- Public search endpoints are still protected by the shared SEARCH rate-limit bucket.
- Personalized recommendation and profile writes require authenticated `SEARCH_READ` or `SEARCH_WRITE` authority.
- Redis outages degrade to in-memory hot keyword and suggestion storage.
- Application methods are traced with `@WithSpan`; key profile and conversion writes are audited.

## Acceptance

- `V37__search_history.sql` and `V38__user_search_profile.sql` are present and validated by `SchemaMigrationTest`.
- `SearchController` exposes products, suggestions, hot keywords, recommendations, profile, and conversions under `/api/search` and `/api/v1/search`.
- `RedisSearchActivityStore` records hot keywords in a Redis ZSET and caches suggestions for one hour.
- `RecommendationEngine` ranks candidates with browse history, purchases, and profile tags.
- Frontend has `SearchView.vue` and `RecommendView.vue` backed by `frontend/src/api/search.ts`.
- `scripts/verify-ws9-search.ps1` and `Ws9SearchWorkflowTest` pass.
