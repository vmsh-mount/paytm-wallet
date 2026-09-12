# Submission — Wallet & P2P Transfer (Paytm PML R2)

**Repo:** <https://github.com/vmsh-mount/p2p-wallet>

Evaluate this by cloning and running it locally (§1) — nothing here depends on
the live deployment. The live URL is optional.

## 1. Evaluate it locally

```bash
git clone https://github.com/vmsh-mount/p2p-wallet.git && cd p2p-wallet
docker compose up --build          # one command: app + Postgres, http://localhost:8080
./scripts/burst.sh localhost:8080  # concurrent get-or-create, idempotent retry storm,
                                    # conservation under contention, no-overdraft race
```

`burst.sh` needs only `bash`, `curl`, `jq` — no test framework, no build step. It
exits non-zero on any assertion failure, so a broken invariant fails the run,
not just prints a warning. [`docs/BUG-INJECTION-DEMO.md`](docs/BUG-INJECTION-DEMO.md)
is a recorded transcript proving that: a deliberately reintroduced overdraft bug
makes the script fail, with a real (not staged) failure output.

For the full black-box scenario suite (correctness + operational, against
either target) with a generated report:

```bash
./evals/run.sh localhost:8080
```

To run the server-side test suite instead (`InvariantsIT`, `EngineParityIT`,
etc. — the same invariants proven white-box against all three transfer
engines): `./mvnw verify` (needs a JDK 21+ and a Docker daemon for
Testcontainers).
    
## 2. Deliverables checklist

Mapped directly to the brief's "what to send back":

| Deliverable | Where |
|---|---|
| Public repo | <https://github.com/vmsh-mount/p2p-wallet> |
| One-command burst script | [`scripts/burst.sh`](scripts/burst.sh) — §1 above |
| One-page write-up | [`docs/WRITEUP.md`](docs/WRITEUP.md) (longer form: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)) |
| Live URL *(optional)* | <https://p2p-wallet.onrender.com> |
| Public logs link *(optional)* | Render's log view is account-gated; a burst run's structured-log transcript is captured in the eval report below instead |

**Observability, live** (not on the brief's "send back" list by name, but "genuine
deploy/containerize/observe" is graded — both are public, no auth):

| | |
|---|---|
| Metrics (Prometheus text — RED + domain counters) | <https://p2p-wallet.onrender.com/metrics> |
| Dashboard (polls `/metrics` every 3s) | <https://p2p-wallet.onrender.com/dashboard> |

**Proof of correctness, already run and committed:**

| Artifact | What it shows |
|---|---|
| [`evals/reports/20260911T050309Z.md`](evals/reports/20260911T050309Z.md) | Full scenario suite against the **live deployment** — 7/7 scored scenarios, exit `0` |
| [`docs/BUG-INJECTION-DEMO.md`](docs/BUG-INJECTION-DEMO.md) | A deliberately reintroduced invariant bug, and the harness catching it |
| [`bench/RESULTS.md`](bench/RESULTS.md) | Throughput/latency comparison across all three transfer-engine candidates |
| CI (green on `main`) | `./mvnw verify` + container hardening checks + the eval harness, all run on every push |

## 3. Where to read more

- [`README.md`](README.md) — stack, full API reference, status-code contract, how to run/deploy
- [`docs/WRITEUP.md`](docs/WRITEUP.md) — data model, the simplest-correct mechanism and what was
  rejected, where idempotency lives, consistency-vs-availability, **AI directed-vs-decided**, cost note
- [`docs/plan/`](docs/plan/README.md) — the 14-task delivery plan this was built against, one
  branch/PR per task, each with its own scope/design/acceptance-criteria/test-plan
- [`evals/`](evals/README.md) — every scenario spec plus the traceability matrix
  ([`evals/matrix.md`](evals/matrix.md)) mapping each invariant to its schema guard, unit test,
  integration test, and black-box probe
- README's "Observability" sections (Logs, Metrics & Dashboard) — event taxonomy, correlation id,
  what each metric means; [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the design rationale
