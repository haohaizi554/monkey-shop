# WS1 Catalog Center

## Goal

WS1 turns the legacy Monkey product CRUD into a catalog center with SPU/SKU, a
three-level category tree, category-bound attribute templates, runtime pricing,
and governed product status transitions.

The repository already contains `V18__user_email_pii_encryption.sql`, so this
work stream uses `V19` through `V21` to avoid a Flyway version collision:

- `V19__product_spu_sku.sql`
- `V20__product_category_tree.sql`
- `V21__product_attribute_template.sql`

## Business Scope

- SPU is the aggregate root. SKU is an entity inside the SPU aggregate.
- SKU rows are generated from specification Cartesian products.
- Products can bind only to level-3 categories.
- Category attribute templates store dynamic attributes in MySQL JSON columns.
- Price strategy routes original, member, strike-through, and regional prices
  by user identity and region.
- Product status moves through
  `DRAFT -> PENDING_REVIEW -> APPROVED -> LISTED -> UNLISTED -> RECYCLED`.
  Illegal transitions fail closed.
- Rich product details can be represented as JSON-LD and continue to reuse
  `frontend/src/seo/product-json-ld.ts`.

## Technical Invariants

- SPU/SKU IDs use `shared.domain.id.IdGenerator` implemented by
  `shared.infrastructure.id.SnowflakeIdGenerator`.
- Supplier-private product remarks use
  `EncryptedStringAttributeConverter`, so Tink PII encryption protects the
  persisted value when encryption is enabled.
- Product image upload continues through `FileService` and the existing
  Magic/Tika/ClamAV pipeline.
- GET catalog endpoints are mapped to the existing `SEARCH` three-dimensional
  rate limit policy.
- Category trees are cached in Redis key `catalog:category-tree:v1` for one
  hour.
- Domain classes stay framework-free and expose only records, enums,
  interfaces, and final classes.
- Critical mutations record audit events through `AuditService`.
- Application operations are annotated with `@Transactional`; reads use
  `@Transactional(readOnly = true)`.

## Acceptance

- Creating an SPU from two spec dimensions creates all SKU combinations.
- Non-level-3 categories are rejected.
- Illegal product status transitions throw and do not persist.
- Member and regional prices route deterministically.
- `/api/catalog/**` and `/api/v1/catalog/**` GET requests use SEARCH rate
  limiting and can return `429` with `Retry-After` through the existing filter.
- `scripts/verify-ws1-catalog.ps1` and `Ws1CatalogWorkflowTest` pass.
