-- C1 wave A: tenant-wide visibility for API keys (D-C1-12) and the role that gates
-- minting one (D-C1-13). scope marks what a key can see: USER keys stay per-creator,
-- TENANT keys act as their creator but read every link in their tenant. The founder
-- created by register is the tenant's ADMIN; everyone else defaults to MEMBER.
ALTER TABLE lf_api_key ADD COLUMN scope VARCHAR(16) NOT NULL DEFAULT 'USER' COMMENT 'USER|TENANT; TENANT = tenant-wide visibility (D-C1-12)';
ALTER TABLE lf_user ADD COLUMN tenant_role VARCHAR(16) NOT NULL DEFAULT 'MEMBER' COMMENT 'MEMBER|ADMIN; ADMIN may mint TENANT keys (D-C1-13)';
