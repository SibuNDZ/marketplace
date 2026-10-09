-- V37__order_payment_reference.sql
-- Numbered V37: this change was first written as V32, then V35, and main
-- shipped V33 to V36 ahead of it each time. Flyway has out-of-order off, so a
-- version below the newest applied one stops the API booting, and a duplicate
-- version is refused outright. The V33 and V34 headers still say V32 is
-- reserved for this change; they cannot be edited, because an applied
-- migration's checksum covers its comments.
-- The provider's id for the payment that moved an order to PAID. Without it
-- a second, separately paid transaction for the same order looks exactly
-- like a webhook redelivery and is swallowed as a duplicate, so the customer
-- is charged twice with no refund trail. PaymentEventService compares an
-- incoming reference with this one and raises MANUAL REFUND REQUIRED when
-- they differ.
--
-- Nullable: orders paid before this column existed, and providers that do
-- not pass a reference yet, keep today's behaviour.

ALTER TABLE orders ADD COLUMN payment_reference VARCHAR(100);
