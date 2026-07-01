# WS2 RBAC Permission Matrix

MonkeyShop uses permission authorities for API authorization. `ROLE_USER` and
`ROLE_ADMIN` remain identity labels, but controllers and the Spring Security
request matrix must authorize with concrete permissions instead of role-only
guards.

## Roles

| Role | Permissions |
| --- | --- |
| `USER` | `USER_PROFILE_READ`, `USER_PROFILE_WRITE`, `ADDRESS_MANAGE`, `ORDER_CREATE`, `ORDER_READ_OWN`, `ORDER_RETURN_REQUEST`, `UPLOAD_AVATAR` |
| `ADMIN` | All current permissions: every `USER` permission plus `ADMIN_DASHBOARD_READ`, `PRODUCT_MANAGE`, `ORDER_MANAGE`, `UPLOAD_PRODUCT_IMAGE` |

## Permission Semantics

| Permission | Scope |
| --- | --- |
| `USER_PROFILE_READ` | Read the current user's profile. |
| `USER_PROFILE_WRITE` | Update profile, password, avatar, and forget-me requests. |
| `ADDRESS_MANAGE` | Create, read, update, and delete the current user's addresses. |
| `ORDER_CREATE` | Create an order for the authenticated user. |
| `ORDER_READ_OWN` | Read, receive, hide, and delete only owned orders. |
| `ORDER_RETURN_REQUEST` | Apply for and ship returns only for owned orders. |
| `UPLOAD_AVATAR` | Upload and presign avatar images. |
| `ADMIN_DASHBOARD_READ` | Read administrative metrics and audit-trace views. |
| `PRODUCT_MANAGE` | Create, update, delete, and upload images for products. |
| `ORDER_MANAGE` | Ship orders and approve or confirm returns across users. |
| `UPLOAD_PRODUCT_IMAGE` | Upload and presign product images. |

## Invariants

- Non-public controller methods must use permission guards such as
  `hasAuthority(...)`, not `hasRole(...)`, `hasAnyRole(...)`, or bare
  `isAuthenticated()`.
- Owned order mutations must combine the relevant permission with
  `@orderOwnership.isOwner(#id, authentication)`.
- The Flyway migration `V6__rbac_roles_permissions.sql` is the source of truth
  for bootstrapped role-permission grants.
- `ADMIN` is intentionally granted all current permissions so operations staff
  can use both user-support and administration paths with one account.
