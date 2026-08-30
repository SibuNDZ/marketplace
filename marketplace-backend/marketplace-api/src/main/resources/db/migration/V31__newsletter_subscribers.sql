-- V31__newsletter_subscribers.sql
-- The landing page's "Join the inner circle" form stores REAL
-- subscriptions; a form that pretends would break the honesty rules.
-- Email is the natural key: subscribing twice is one row, and the endpoint
-- answers 202 either way so it cannot be used to probe who subscribed.

CREATE TABLE newsletter_subscribers (
    id          BIGSERIAL PRIMARY KEY,
    email       VARCHAR(254) NOT NULL UNIQUE,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);
