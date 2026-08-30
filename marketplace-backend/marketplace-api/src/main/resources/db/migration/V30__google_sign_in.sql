-- V30__google_sign_in.sql
-- "Continue with Google" (google-signin.md).
--
-- google_sub is Google's stable subject identifier — the join key for a
-- Google-backed account. Email is deliberately NOT the key: emails can change
-- at Google, sub cannot.
--
-- password becomes nullable because a Google-only account has no password.
-- The login path treats a NULL hash exactly like an unknown email (same
-- generic 401, same dummy-hash timing), and resetPassword works unchanged as
-- the route to adding a password later.

ALTER TABLE users ADD COLUMN google_sub VARCHAR(255);
ALTER TABLE users ADD CONSTRAINT uq_users_google_sub UNIQUE (google_sub);

ALTER TABLE users ALTER COLUMN password DROP NOT NULL;
