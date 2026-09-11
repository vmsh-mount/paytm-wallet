# Submission — Wallet & P2P Transfer (Paytm PML R2)

**Repo:** <https://github.com/vmsh-mount/p2p-wallet>

Everything below can be verified from the repo alone — cloning and running it
locally is the primary way to evaluate this, not the live deployment. The live
URL is a convenience (it's a free-tier instance that sleeps and eventually
expires — see [§3](#3-live-deployment-optional)); nothing here
depends on it staying up.

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
| Live URL *(optional — see §3)* | <https://p2p-wallet.onrender.com> |
| Public logs link *(optional — see §3)* | Render's log view is account-gated; a burst run's structured-log transcript is captured in the eval report below instead |

**Proof of correctness, already run and committed:**

| Artifact | What it shows |
|---|---|
| [`evals/reports/20260911T050309Z.md`](evals/reports/20260911T050309Z.md) | Full scenario suite against the **live deployment** — 7/7 scored scenarios, exit `0` |
| [`docs/BUG-INJECTION-DEMO.md`](docs/BUG-INJECTION-DEMO.md) | A deliberately reintroduced invariant bug, and the harness catching it |
| [`bench/RESULTS.md`](bench/RESULTS.md) | Throughput/latency comparison across all three transfer-engine candidates |
| CI (green on `main`) | `./mvnw verify` + container hardening checks + the eval harness, all run on every push |

## 3. Live deployment (optional)

<https://p2p-wallet.onrender.com> — Render free web service + free managed
Postgres, ₹0, deployed 2026-09-11. Public endpoints: [`/dashboard`](https://p2p-wallet.onrender.com/dashboard),
[`/metrics`](https://p2p-wallet.onrender.com/metrics), `/healthz`, `/actuator/health`.

Free-tier caveats, so a dead link isn't mistaken for a broken submission:

- **Cold start** — the service sleeps after ~15 min idle; the first request
  after that takes ~30–50s while Render restarts the container. `GET /healthz`
  is a dependency-free way to warm it before running anything else.
- **DB lifetime** — the managed Postgres instance expires **~30 days after
  creation** (created 2026-09-11 → expect ~2026-10-11). After that the live URL
  stops responding; that's the free-tier expiry, not a regression.
  `docker compose up --build` (§1) reproduces the identical image and
  migrations locally, indefinitely.

If you want to run the burst probes against the live URL yourself:

```bash
./scripts/burst.sh https://p2p-wallet.onrender.com
```

The dev bearer tokens are still enabled on this deployment for grading. There's
no deposit API by design (money only enters via a transfer from an
already-funded wallet), so without direct database access the probes exercise
the `DECLINED` and idempotent-replay paths rather than `COMPLETED` — the
script says so in its own output, it isn't a failure. The linked eval report
above was run with database access and confirms the `COMPLETED` path and its
counters too.

## 4. Where to read more

- [`README.md`](README.md) — stack, full API reference, status-code contract, how to run/deploy
- [`docs/WRITEUP.md`](docs/WRITEUP.md) — data model, the simplest-correct mechanism and what was
  rejected, where idempotency lives, consistency-vs-availability, **AI directed-vs-decided**, cost note
- [`docs/plan/`](docs/plan/README.md) — the 14-task delivery plan this was built against, one
  branch/PR per task, each with its own scope/design/acceptance-criteria/test-plan
- [`evals/`](evals/README.md) — every scenario spec plus the traceability matrix
  ([`evals/matrix.md`](evals/matrix.md)) mapping each invariant to its schema guard, unit test,
  integration test, and black-box probe

---
Verified 2026-09-11: fresh-clone `docker compose up --build` → `burst.sh` green locally; the same
probes green against the live deployment; `./mvnw verify` green in CI.
