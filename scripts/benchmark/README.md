# BoardWallet local benchmarks

This is an extension beyond the course examples. JWT construction matches the
project's Base64-decoded HS256 key and issuer (the OAuth2 Resource Server pattern
from Lecture 38). No application source, schema, or security configuration is
changed. The benchmark runner is restricted to localhost:8080.

## Start the application

From the boardwallet project directory, start with these **environment overrides**
in the IDE run configuration, or export them in the terminal used to launch Maven:

```sh
export SPRING_JPA_SHOW_SQL=false
export SPRING_JPA_PROPERTIES_HIBERNATE_FORMAT_SQL=false
export LOGGING_LEVEL_ORG_HIBERNATE_SQL=OFF
export LOGGING_LEVEL_ORG_HIBERNATE_ORM_JDBC_BIND=OFF
./mvnw spring-boot:run
```

SQL logging would otherwise add substantial console I/O to the benchmark. Keep
other clients idle and leave server/pool/database settings constant across runs.
Do not start the load run until the application reports that it has started.

## Installed local tools and commands

k6 is installed through Homebrew. The Python environment is
`~/.local/share/boardwallet-seed/venv`; dependencies are pinned in requirements.txt.
The existing million-user dataset is required. Preparation is read-only:

```sh
~/.local/share/boardwallet-seed/venv/bin/python scripts/benchmark/bench.py prepare
```

From the project directory, run stages individually, inspecting results before
increasing load. These commands are **not run automatically**:

```sh
# 10-request route/authentication sanity check
~/.local/share/boardwallet-seed/venv/bin/python scripts/benchmark/bench.py run --mode smoke

# Short warm-up (report separately); then 100, 250, 500, 1000 RPS stages
~/.local/share/boardwallet-seed/venv/bin/python scripts/benchmark/bench.py run --mode read --rate 10 --seconds 30
~/.local/share/boardwallet-seed/venv/bin/python scripts/benchmark/bench.py run --mode read --rate 100 --seconds 60
~/.local/share/boardwallet-seed/venv/bin/python scripts/benchmark/bench.py run --mode read --rate 1000 --seconds 180 --pre-vus 500 --max-vus 2000

# Read/payment mix: roughly 90% reads, 10% payments
~/.local/share/boardwallet-seed/venv/bin/python scripts/benchmark/bench.py run --mode mixed --rate 100 --seconds 60

# Same two wallets, opposite directions; 50 concurrent VUs, 10 requests each
~/.local/share/boardwallet-seed/venv/bin/python scripts/benchmark/bench.py run --mode hot --vus 50
~/.local/share/boardwallet-seed/venv/bin/python scripts/benchmark/bench.py run --mode idempotency --vus 50
~/.local/share/boardwallet-seed/venv/bin/python scripts/benchmark/bench.py run --mode conflict
~/.local/share/boardwallet-seed/venv/bin/python scripts/benchmark/bench.py run --mode security

# Run last: deliberately spends most/all of one test wallet's balance
~/.local/share/boardwallet-seed/venv/bin/python scripts/benchmark/bench.py run --mode overspend

# Separate expensive authentication benchmark
~/.local/share/boardwallet-seed/venv/bin/python scripts/benchmark/bench.py run --mode login --rate 5 --seconds 30

# Fixed 140-second spike/recovery profile: 10 -> requested rate -> 10 RPS
~/.local/share/boardwallet-seed/venv/bin/python scripts/benchmark/bench.py run --mode spike --rate 1000
# Ten-minute sustained run; not a 30–60 minute endurance claim
~/.local/share/boardwallet-seed/venv/bin/python scripts/benchmark/bench.py run --mode soak --rate 100 --seconds 600
```

Every arrival-rate iteration sends one request. 1000 means **offered** requests
per second, not guaranteed achieved throughput. Dropped iterations fail the
threshold. A pool of 10,000 distinct identities is rotated across requests;
this does not claim 10,000 concurrent connections. The smoke run uses only ten
identities. VUs are transport workers and can serve different identities.

Default thresholds are proposed targets, not prior achievements: p95 < 500 ms,
p99 < 1500 ms, zero unexpected responses, all correctness checks passing. Override
with `--p95-ms`/`--p99-ms` before running, and keep targets fixed for comparisons.
Negative scenarios mark their intended rejection status as expected; their
latencies must not be mixed into successful-transfer performance claims.

## Authentication and data changes

Each run verifies a real login, then issues local test JWTs with the existing
secret (never exported into scripts or reports). Tokens expire after 15 minutes;
single runs are capped at 10 minutes. API runs therefore measure JWT validation
and business processing, not login throughput. Login mode measures the real
password endpoint. Login creates refresh-token records.

Mixed/hot/idempotency/conflict/overspend scenarios commit real game transfers.
Idempotency reuses one key across concurrent and repeated requests and requires
exactly one committed effect. Conflict first commits one payment, then changes
its amount while reusing the key and expects 409. Overspend makes 20 requests,
each for half the initial balance, and requires exactly two commits. It must
run last or use a fresh funded fixture afterward. No automatic resets or
compensating payments conceal results. Do not run two benchmarks concurrently.

Security covers missing/malformed/expired tokens, non-member payment attempts,
cross-board recipients and negative amounts. Idempotency is not a claim of
protection against stolen valid bearer tokens or requests with fresh keys.

## Measurements and report files

Private output lives under `~/.local/share/boardwallet-seed/benchmark/runs/`:

- `report.md`, `report.json`: overall outcome, API latency distributions and counters.
- `k6-summary.json`: p50/median, p95, p99, throughput, errors and dropped iterations.
- `metrics.json.gz`: timestamped raw metrics for spike/recovery and time-window analysis.
- `database-report.json`: per-query executions, mean latency, approximate p95/p99,
  rows examined/sent, errors, no-index counters and global deadlock/lock-timeout deltas.
- `db-before.json`, `db-after.json`: raw evidence. Histograms are differenced, not
  reset, so seeding and previous runs are excluded. Snapshots are not atomic;
  report histogram sample-count discrepancies and avoid concurrent external DB work.
- `resource-samples.jsonl`: sampled host CPU/memory, Java/MySQL/k6 process CPU/RSS,
  number of transactions currently waiting, and oldest ongoing lock-wait age.
- `integrity-report.json`: unique committed transfers, wallet ledger reconciliation,
  negative-balance checks, and scenario-specific commit counts. Every affected
  fixture wallet is compared with its starting balance plus this run's transfers.
- `metadata.json`: requested load, environment, source commit, baseline dataset,
  database settings, and thresholds. `k6.log` preserves failures.
- `fixture.json`, `users.json`: temporary test credentials/tokens; private files,
  never attach these to a public report or commit them.

Database p95/p99 use **upper bounds of histogram buckets**, not exact samples.
Statements are grouped by normalized SQL digest, not attributed to HTTP endpoints.
Global deadlock and row-lock counters include all DB-server workloads. Sampled
ongoing wait ages are not complete lock-wait durations and cannot provide an
honest per-request row-lock p95/p99. Query-plan JSON is saved during preparation;
plans indicate candidate indexes, not measured index efficiency by themselves.

Not yet measured: forced mid-transfer rollback, JVM GC pauses, Hikari connection
acquisition percentiles, per-request pure lock-wait distributions, or longer
endurance tests with token renewal. These need additional instrumentation/tests.
Do not claim deadlock recovery if no deadlock actually occurs. Current seed
history contains only starting-money entries; grow realistic histories before
making claims about deep pagination or long-running boards.

## Harness validation

```sh
~/.local/share/boardwallet-seed/venv/bin/python -m unittest discover -s scripts/benchmark -p 'test_*.py' -v
```

Preparation reads the live database and validates fixture/monitor access.
`k6 inspect` validates scenario compilation without starting the application.
Neither step proves the application passes the live tests.

References:
- https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/
- https://grafana.com/docs/k6/latest/results-output/real-time/json/
- https://dev.mysql.com/doc/refman/8.4/en/performance-schema-statement-histogram-summary-tables.html
