-- Task 2.4: manual tracking number on the ship action.
--
-- Free text, nullable: set (optionally) when an order transitions to
-- SHIPPED, by whoever ships it (vendor or admin). Deliberately NOT a
-- courier integration — Bob Go / Shiplogic / Pargo are deferred until
-- manual tracking proves insufficient; this column is the interim that
-- lets a buyer paste a waybill number into a courier's own site.

ALTER TABLE orders ADD COLUMN tracking_number VARCHAR(100);
