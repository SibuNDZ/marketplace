-- Private vendor-to-operator feedback channel (workstream 2).
--
-- Append-only, like order history: there is no updated_at on the message
-- because feedback is a statement made at a point in time, not a document.
-- The only mutable field is the operator-side status flag.

CREATE TABLE platform_feedback (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES users (id),
    category   VARCHAR(20) NOT NULL
        CHECK (category IN ('BUG', 'IDEA', 'COMPLAINT', 'PRAISE', 'OTHER')),
    message    VARCHAR(2000) NOT NULL,
    status     VARCHAR(20) NOT NULL DEFAULT 'NEW'
        CHECK (status IN ('NEW', 'REVIEWED')),
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_platform_feedback_status_created
    ON platform_feedback (status, created_at DESC);
