# Local benchmark dataset

`seed_dataset.py` populates the configured local `boardwallet` database. It
refuses nonempty tables and does not contain a database-clearing operation.
Run while the application and other writers are stopped.

Create a dedicated Python virtual environment, then install
`seed-requirements.txt`. From the project directory:

```sh
python scripts/seed_dataset.py --output /absolute/path/to/private/seed-output
```

The default dataset has 1,000,000 enabled users, 100,000 active boards,
2,200,000 player wallets, 100,000 bank wallets, and 2,200,000 starting-money
transactions. Board sizes are 60% ten players, 30% twenty, and 10% one hundred.
Creators are members, every user belongs to a board, and extra memberships
are randomized without duplicates within a board.

Faker `en_IN` names receive unique numeric suffixes. Fixed seeds and pinned
dependencies reproduce identities and assignments. Timestamps and the shared
test password are generated for each new run. UUIDs have randomized byte
distribution, stored as binary(16), matching Hibernate's UUID representation.
Every player starts with 10,000.00 game units; bank balances are null.

The generator uses SQL batches of up to 1,000 rows (the driver can split them
further by packet size), committing every 10,000 users and every 100 complete
boards with their wallets and starting-money history. Foreign keys and indexes
remain enabled. No application source or schema changes are required.

Output files:

- `checkpoint.json`: committed progress and generation settings.
- `test-credentials.json`: shared test-only password, private to the local user.
- `active-users.jsonl`: 10,000 distinct users and one board/wallet per user.
- `validation-report.json`: counts, distributions, integrity checks and timing.

All fixture users intentionally share one BCrypt cost-10 hash to avoid a
million expensive hashing operations during seeding. This is test data only.
Five stored hashes are checked against the test password after insertion.

To resume an interrupted run, use the same output directory with `--resume`.
The script refuses a checkpoint/database count mismatch. A crash between a
database commit and checkpoint write requires inspection; it never silently
overwrites or ignores duplicate records. Do not run two instances concurrently.

To repeat database validation without inserts:

```sh
python scripts/seed_dataset.py --output /absolute/path/to/private/seed-output --verify-only
```

For a pilot, pass `--users 10000` and a separate output directory. A pilot needs
an empty database too; remove its records deliberately before the full run.

This fixture is an all-active starting-state baseline, not realistic historical
activity: timestamps are identical and history contains only STARTING_MONEY.
Those records correctly have null idempotency keys/hashes, matching the board
starting service. Add realistic subsequent payment history before drawing
conclusions about history pagination or payment-idempotency index performance.
No API, authorization, concurrency, or load test is performed by this script.
