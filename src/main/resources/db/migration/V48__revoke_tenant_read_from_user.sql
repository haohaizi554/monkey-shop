-- V48: Revoke TENANT_READ from USER role.
-- Tenant lifecycle/config/billing/export data is platform-admin scope; a regular
-- customer must not enumerate tenants or read merchant configs. ADMIN retains
-- TENANT_READ/TENANT_WRITE/TENANT_ADMIN from V45.
DELETE FROM role_permissions
WHERE role_id IN (SELECT id FROM roles WHERE name = 'USER')
  AND permission_id IN (SELECT id FROM permissions WHERE name = 'TENANT_READ');
