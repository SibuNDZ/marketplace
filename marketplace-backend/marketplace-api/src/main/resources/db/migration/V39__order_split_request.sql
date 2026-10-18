-- V39__order_split_request.sql
-- The split object exactly as sent on the Paystack initialize request
-- (paystack-subaccounts.md §6.1), so the charge.success webhook can
-- reconcile what Paystack reports against what we asked for, and so an
-- absent data.split on an order we DID split is detectable
-- ("SPLIT MISSING ON CHARGE.SUCCESS").
--
-- JSON as text: subaccount codes and cents only, never an account number.
-- NULL for every order that was not split (all orders until the split
-- switch is on, and any order whose vendors have no subaccount).

ALTER TABLE orders ADD COLUMN split_request TEXT;
