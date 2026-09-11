# Media

No static screenshots/recordings are checked in here — everything they'd show is live and public,
so a stale image would just go out of date. Instead:

- **Dashboard (live):** <https://p2p-wallet.onrender.com/dashboard> — polls `/metrics` every 3s,
  shows request rate, HTTP p99, error rate, and the domain counters (completed/declined/replayed
  transfers, transfer p99, serializable retries).
- **Raw metrics (live):** <https://p2p-wallet.onrender.com/metrics>
- **Burst probes, live:** run `./scripts/burst.sh https://p2p-wallet.onrender.com` yourself —
  every PASS/FAIL line prints to the terminal in real time; a captured transcript from the same
  command is in [`evals/reports/20260911T050309Z/burst.log`](../../evals/reports/20260911T050309Z/burst.log).
- **Logs:** Render's dashboard log view is account-gated (no public link on the free tier) — see
  [`docs/WRITEUP.md`](../WRITEUP.md) / README for the local structured-JSON log examples instead.
