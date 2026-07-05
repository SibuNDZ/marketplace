# Hibernate Locking Pattern: Lock the Scalar, Reload the Entity

## The Recurring Shape

This project has hit the same bug twice. Both instances have identical structure:
a correctly-acquired database lock paired with Hibernate serving cached entity
state that predates it. The lock was real; the entity read after it was not.

**Instance 1 — product oversell (initial checkout implementation)**
`lockAndRefresh` exists precisely because of this: `ProductRepository.findAllByIdForUpdate`
acquires `SELECT ... FOR UPDATE`, but without the subsequent `entityManager::refresh`
call, the Hibernate first-level cache returns the product entity as it was loaded
*before* the lock query — stale stock, concurrent decrement, oversell.

**Instance 2 — cart double-submit (V6 slice)**
`CartRepository.findByUserIdForUpdate` was implemented three ways before it worked:

1. `@Lock(PESSIMISTIC_WRITE)` on `WHERE c.user.id = :userId` (JPQL, association path)
   → never emitted `FOR UPDATE` to PostgreSQL; both concurrent threads succeeded.

2. Native `SELECT * FROM carts WHERE user_id = ? FOR UPDATE` returning `Optional<Cart>`
   → emitted `FOR UPDATE`, but the Cart entity entered the first-level cache;
   the subsequent `findWithItemsByUserId` (EntityGraph) served Hibernate's cached
   entity to Thread B instead of reading post-commit DB state; both threads
   succeeded again.

3. Native `SELECT id FROM carts WHERE user_id = ? FOR UPDATE` returning `Optional<Long>`
   ← **this is the working version.** Scalar result: nothing enters the session
   cache. The follow-up `findWithItemsByUserId` executes a clean EntityGraph query
   against committed DB state. Thread B sees 0 items, throws `EmptyCartException`.

## The Rule

> When acquiring a pessimistic lock through a lookup (not by primary key),
> issue `SELECT <pk_column> ... FOR UPDATE` (scalar — nothing in cache),
> then reload the entity separately.

Do **not** return the locked entity from the same query. Even when the `FOR UPDATE`
is genuine, the entity in the first-level cache reflects the snapshot at load time,
not the post-lock committed state. The next reader in a competing transaction that
goes through the same session sees stale state.

This is the same reason `lockAndRefresh` exists for products: lock first via a
query that returns the managed entity, then `entityManager.refresh()` to evict the
cache snapshot. The scalar-lock approach is cleaner because it prevents the stale
entry from ever being created.

## When This Does and Doesn't Apply

**Applies:** any lock acquired via a WHERE clause that returns and manages an entity
(JPQL `@Lock`, `EntityManager.lock()`, or native query with entity return type).

**Does not apply:** `EntityManager.find(Class, id, LockModeType)` — JPA find-with-lock
guarantees a fresh database read; the first-level cache is intentionally bypassed.
This is why `lockAndRefresh` *could* alternatively use `em.find(..., PESSIMISTIC_WRITE)`
instead of query + refresh, and would be correct.

## Hibernate 6 Note: @Lock on Association-Path WHERE Clauses

`@Lock(PESSIMISTIC_WRITE)` on a Spring Data JPA `@Query` whose WHERE clause
traverses a `@OneToOne`/`@ManyToOne` path (e.g., `WHERE c.user.id = :userId`)
does not reliably emit `FOR UPDATE` in Hibernate 6. The mechanism is Hibernate's
"follow-on locking" — when a query shape prevents inline locking (joins, collection
fetches, some association paths), Hibernate issues a separate `SELECT ... FOR UPDATE`
per entity *after* the main query. Whether this separate lock fires, and whether it
blocks competing transactions in the expected window, is non-deterministic under
concurrent load.

The only reliable lock path confirmed in this project is a native query with
`FOR UPDATE` in the SQL text itself, on a simple `WHERE pk_column = ?` or
`WHERE fk_column = ?` (no association navigation, no JOIN).

## Third-Occurrence Warning

The next place this will bite is any service method that:
1. Loads an entity (putting it in the first-level cache)
2. Then calls a lock-acquiring query on that same entity
3. Then reads the entity's state and acts on it

The cached pre-lock state will silently defeat the lock's purpose. Watch for this
pattern in `cancelExpired` (uses `findByIdForUpdate` + entity state check) —
there it is correct because `findByIdForUpdate` is the *first* load, so the cache
is cold. If an earlier load is ever added before `findByIdForUpdate`, the refresh
step must be added too.
