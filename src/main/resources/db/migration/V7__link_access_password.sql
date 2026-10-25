-- B3: password-protected short links. NULL = not protected; the hash never leaves the DB.
ALTER TABLE lf_short_link ADD COLUMN access_password_hash VARCHAR(100) NULL;
