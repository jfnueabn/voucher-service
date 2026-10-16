# NOTES

## What I changed and why

**Per-user redemption limit.**

- `campaign.max_redemptions_per_user INT NOT NULL DEFAULT 2` (with a `>= 1` check). The seed data doesn't list this column, so existing campaigns get the default of 2. A real database would need `ALTER TABLE campaign ADD COLUMN ... DEFAULT 2`. The repo has no migration tool; it drops and recreates the schema on boot.
- **What counts toward the limit:** the user's redemption rows in that campaign, *excluding vouchers that were voided afterwards*. A voided redemption frees the slot. So seeded `user-99` (one redeemed voucher, one voided) is at 1 of 2 and can redeem once more.
- Rejected with `FAILED` / `"Per-user redemption limit reached for this campaign (2 of 2)"`. This uses the same response shape as the existing failure reasons.

**Making the check safe under concurrency.** Counting existing redemptions and then inserting a new one is a race: the same user firing two requests at once would get both through. So `redeem` is now:

- `@Transactional`
- locking the voucher row and then the campaign row (`SELECT ... FOR UPDATE`) before any checks. Every redemption in a campaign is serialised on that campaign's row.

The locks are held by the database, not the JVM, so they still work with several app instances on one shared database. They also fix two existing bugs: two requests could redeem the same voucher twice, and concurrent `remaining_stock` decrements could be lost.

**Audit call moved to after commit.** Once `redeem` is transactional, a remote HTTP call inside it would hold the row locks for the length of a network round trip. It could also audit a redemption that later rolled back. It now runs in `afterCommit`, with the same swallow-and-log behaviour as before.

**Tests.**

- `RedemptionLimitTest` covers the default, the `user-99` voided case, rejection with no side effects, other users, per-campaign scope, a custom limit, and voiding to free a slot. Each case runs in a rolled-back transaction with its own fixture campaign.
- `RedemptionConcurrencyTest` fires 8 parallel redeems for one user against a limit of 1. I checked it catches the race: with the locks removed it lets all 8 through.

## Noticed but deliberately did not change

- **Hard-coded audit API key in `AuditClient`.** It's in source and sent in the request body. It needs rotating and moving to config or a secret store. Rotation needs coordinating with whoever owns the audit service, so I flagged it rather than changed it.
- **Failures return HTTP 200 with `result: FAILED`.** That's poor REST, but the storefront depends on it. Switching to 4xx/409 is a contract change, not part of this feature.
- **`voidVoucher`** takes no lock, doesn't restore `remaining_stock`, will void an already-redeemed voucher, and isn't audited. Under my counting rule, voiding a redeemed voucher frees the user's slot but not the stock. That inconsistency is worth a conversation about what "void" means in the business.
- **Stats** make one query per voucher (N+1). `redeemedCount` also counts vouchers voided after redemption as redeemed.
- **Seed data drift:** campaign 1 says `remaining_stock = 100` but has 6 vouchers. Stock is a separate counter, not derived from the vouchers.
- `findById(...).get()` turns an unknown campaign into a 500 error. `voucher.code` has no unique constraint, and there are no foreign keys.
- `userId` isn't validated, so a blank string counts as a user.
- The audit failure log drops the exception, so failures can't be diagnosed.
- Field injection, `String` status values instead of an enum, and `java.util.Date` are style points, not worth the churn here.

## What I'd do next with another day

- Index `redemption(campaign_id, user_id)` for the limit count.
- Add a unique constraint on `voucher.code` and foreign keys.
- Move the audit key to config and rotate it. Make audit delivery reliable with an outbox table rather than fire-and-forget.
- Decide the "void" semantics with the business, then make `voidVoucher` consistent: lock, stock, audit.
- Add Flyway so schema changes are real migrations.
- Return a machine-readable failure code alongside the message, for example `USER_LIMIT_REACHED`, so the storefront doesn't match on strings.
- Expose the limit, and the user's remaining allowance, in the stats or a lookup endpoint.

## Running on 3 instances behind a load balancer

As configured, each instance has its **own in-memory H2 database**, seeded on boot. Each voucher can then be redeemed once per instance, a user's limit is effectively 3× the setting, stock is tracked three times, and stats depend on which instance answers. Restarting an instance resets its data. The service needs a shared database first.

With a shared database, the new row locks hold across instances. Before this change, the voucher and stock checks were check-then-act races that more instances would make worse.

## Reflections

> TODO (your own words):
> - What did you get wrong first, and how did you notice?
> - Which AI suggestion did you reject, and why?
> - What took you longest?
