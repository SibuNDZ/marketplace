-- Vendors trade under a business name, not a personal one.
--
-- Nullable at the column level on purpose: buyers have no business name, and
-- a NOT NULL with a '' default would just move the emptiness somewhere less
-- visible. "Required for vendors" is enforced where the role is known — at
-- registration and at the buyer->vendor upgrade — not by a constraint that
-- would also have to be true for every customer row.
--
-- Existing vendors are backfilled from their personal name because that is
-- literally what their listings already display today (ProductService used
-- getFullName()). Backfilling keeps every live product card rendering the
-- same string it rendered before this migration; vendors can then correct it
-- to a real trading name from account settings. A blank backfill would have
-- silently emptied the vendor line on every existing listing.

ALTER TABLE users ADD COLUMN business_name VARCHAR(200);

UPDATE users
   SET business_name = NULLIF(TRIM(COALESCE(first_name, '') || ' ' || COALESCE(last_name, '')), '')
 WHERE role = 'VENDOR'
   AND business_name IS NULL;
