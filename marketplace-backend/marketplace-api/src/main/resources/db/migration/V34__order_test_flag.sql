-- V34__order_test_flag.sql
-- Marks an order placed while checkout was guarded (payments in TEST mode,
-- checkout limited to admins; see CheckoutPolicy).
--
-- A test-mode payment moves no money, but until now the system could not
-- tell: the order went PAID, the vendor was emailed "a customer paid", a
-- payout entry was written and the product gained a public "sold". With this
-- flag set, the order behaves like a test everywhere a sale has an effect:
-- no vendor email, no payout entry, not in the vendor's dashboard, not in any
-- sold or recent-buyer count, and no review eligibility.
--
-- Set once, at placeOrder, and never changed: it records the conditions the
-- order was placed under, not the current mode. An order placed under live
-- payments stays real even if the site is later put back into test mode.
--
-- DEFAULT FALSE for every existing row, deliberately. Past test orders
-- predate the guard and are cleaned up one by one with the admin void
-- (POST /api/v1/admin/orders/{id}/void), where a person looks at each. A
-- blanket backfill would be a guess about which historical orders were real.
--
-- NOT V32 or V33: V33 is the seller referral source on main, and V32 is
-- claimed by the unmerged Paystack branch, which now needs renumbering past
-- this one. Flyway runs with out-of-order disabled.

ALTER TABLE orders
    ADD COLUMN test_order BOOLEAN NOT NULL DEFAULT FALSE;
