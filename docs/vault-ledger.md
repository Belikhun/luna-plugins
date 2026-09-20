# LunaVault: the ledger

How money moves on the Luna network after the 2026-09 rewrite, and why it is
built this way. Read this before touching `core/luna-vault-api`, `velocity/luna-vault`
or any `luna-vault-backend` module.

## One writer

The Velocity proxy is the only process that changes a balance. Every backend,
whatever database its own LunaCore is pointed at, is a client: it asks the proxy
over the plugin-message bus and caches the answers. `VaultLedger` (in
`luna-vault-api`, run only on the proxy) is the sole code path that writes
`vault_accounts` and `vault_transactions`.

The previous design let a backend with a database write balances itself and
push the *absolute* result to the proxy through an outbox. That produced lost
updates whenever two servers touched one account inside a second, and the lobby,
which runs on its own SQLite file, pushed stale copies of every balance it saw.
None of that path survives.

## One transaction per operation

A deposit, withdrawal, transfer or set is one database transaction on one
connection:

1. Lock the account rows involved (`SELECT ... FOR UPDATE` on MariaDB; SQLite
   opens the transaction `IMMEDIATE`), always in UUID order so two transfers
   crossing each other cannot deadlock.
2. Apply the change as a conditional update:
   `UPDATE ... SET balance_minor = balance_minor + ? WHERE ... AND balance_minor >= ?`.
   The condition is what makes an overdraft impossible under any interleaving:
   the second of two racing withdrawals finds the row no longer qualifies and
   changes nothing.
3. Insert the ledger row, carrying the running balance of each side
   (`sender_balance_after`, `receiver_balance_after`).
4. Commit.

Nothing is read into memory and written back. A refused operation (insufficient
funds, bad amount) rolls back and writes no row.

Contention (a MariaDB deadlock, a busy SQLite file) is retried a few times with
backoff inside the ledger; a duplicate operation id is answered from the record.

## Idempotency

Every mutating request carries an `operationId`. The ledger row stores it under
a unique index, so:

- the same request delivered twice is applied once and answered twice with the
  same outcome (`LedgerResult.replayed`);
- a backend whose reply was lost can ask `LOOKUP <operationId>` and learn what
  happened, which the client does automatically after a timeout;
- an operator can ask `/eco lookup <operationId>` on the proxy.

Refused operations are deliberately *not* recorded: a retry should be judged
against the balance as it stands then.

## Auditability

Because each row carries running balances, the ledger can be reconciled:
an account's balance must equal the running balance on the newest row naming
it. `/eco audit <player>` and `/eco audit all` do exactly that and list every
account that disagrees, with the row it disagrees with. Imported balances (no
row) are counted, not flagged.

## Caches that never lie to zero

Backends hold a `VaultPlayerStateCache`. Two rules:

- Every snapshot carries a **version** stamped by the proxy that only grows
  (wall clock, forced strictly increasing). A backend keeps the newer snapshot
  when a push and a reply cross on the wire.
- Nothing turns a known balance into "unknown" on the proxy's behalf. After a
  mutation the proxy pushes the affected players' snapshots **only to the
  servers those players are on**. A bulk invalidation (`/eco importbalances`)
  marks entries *stale*; a stale entry is still served while a fresh one is
  fetched.

The old `clearAll` on every mutation, which emptied every backend's cache and
made every balance read zero until refetched, is gone.

## Transport

- Requests ride an online player's connection (plugin message) or the AMQP bus.
  With nobody online the bus is still asked: the AMQP transport publishes on
  the server's own behalf; the plugin-message fallback answers `NO_ROUTE`.
- The proxy answers to the **server** the frame came from (`RegisteredServer`),
  never to the carrier player, so a player switching servers mid-request no
  longer strands the reply inside a Minecraft client.
- Every frame starts with a protocol version (`VaultRpcProtocol.VERSION`). A
  mismatch is logged on both sides, once a minute per peer, and the frame is
  dropped; the proxy and every backend jar must be deployed together.
- Client time budget: one configured timeout per request (`transport.timeout-millis`,
  default 3 s). Resends happen only for failures that come back at once (bus
  refused, carrier gone). A request that has waited the whole budget is
  reported `TIMEOUT` and a settlement lookup is scheduled (1 s, 3 s, 7 s later).
- Mutation futures never complete exceptionally: the caller gets a failed
  `VaultOperationResult` naming the reason.

## Threads

- Proxy: all ledger work runs on a fixed pool (`ledger.threads` in
  `luna-vault/config.yml`, default 4). No database call runs on a Velocity
  event thread. A second delivery of an operation already in flight joins it.
- Backend: one daemon scheduler thread owns timeouts and settlement; replies
  complete on the thread the bus delivered on. The Vault `Economy` adapter still
  blocks the main thread for a write, because that is Vault's contract, but a
  write is now one round trip and one transaction.

## Schema

Migration `lunavault` v3 adds to `vault_transactions`: `operation_id` (unique),
`kind` (`DEPOSIT|WITHDRAW|TRANSFER|ADJUST`), `sender_balance_after`,
`receiver_balance_after`; adds `vault_accounts (balance_minor, player_uuid)`
for rank and leaderboard queries; drops `vault_sync_outbox`.

## Testing without logging in

- `./gradlew :luna-vault-api:test` runs the ledger against a real SQLite file:
  atomicity, overdraw races, idempotent replay, audit, codec round trips.
- Backend console (`luna send survival lunavault ...`):
  `status`, `balance <player>`, `deposit|withdraw <player> <amount>` (through
  the Vault provider, as third-party plugins call it), `transfer`, `history`,
  `stress <player> <rounds> <amount>` (N deposits and N withdrawals at once;
  the balance must come back to where it started) and
  `overdraw <player> <attempts> <amount>` (exactly `min(attempts, balance/amount)`
  may succeed). The mod-loader builds expose the same command.
- Proxy console (`luna send proxy eco ...`): `get`, `add`, `take`, `set`,
  `audit <player>|all`, `lookup <operationId>`.
- Console HTTP: `GET /api/vault/accounts/{player}` and
  `/api/vault/accounts/{player}/transactions` on the proxy's LunaCore server,
  gated on the forwarding secret; rows now carry `kind` and `balanceAfter*`.

## Not done here

`core/luna-legacy-api` (the 1.12.2 trunk) still holds the previous gateway and
wire format. No 1.12.2 backend is registered in the cluster; when one is, port
`VaultClient` into it the way `VaultGateway<P>` was, and bump nothing on the
proxy: the protocol header already refuses the old frames cleanly.
