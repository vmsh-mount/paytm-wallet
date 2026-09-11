# TASK-13 — Write-up & submission package

| | |
|---|---|
| **Status** | Done |
| **Branch** | `task/13-writeup-submission` |
| **Depends on** | all |
| **Invariant(s)** | — |
| **Est. effort** | ~2h |

## Context

Assemble the deliverables and finalize the **one-page** write-up. Most sections
were filled incrementally by earlier tasks; this task edits for length, coherence,
and honesty, and produces the submission checklist.

## Goal

A crisp one-page `docs/WRITEUP.md` and a `SUBMISSION.md` with every required link,
all verified live the day of submission.

## Scope

### In scope
- Trim `docs/WRITEUP.md` to **one page** (print/PDF check), sections:
  1. Data model (+ rejected ledger design)
  2. Simplest-correct mechanism + heavier alternatives rejected (cite benchmark)
  3. Where idempotency lives (same-tx commit; 409 path)
  4. Consistency vs availability (CP; what was given up)
  5. AI: directed vs decided (table, honest, specific)
  6. Free-tier cost note (₹0 + caveats)
- `SUBMISSION.md`: live URL, repo URL, public logs link, dashboard link,
  one-command burst invocation, write-up link, eval report link
- `docs/ARCHITECTURE.md` (optional, longer form the write-up references)
- `docs/media/` — burst screen-recording, dashboard screenshot, logs screenshot
- Final pass: every link in every doc resolves; `burst.sh` + `smoke.sh` green
  against the live URL; CI green on `main`
- Tag `v1.0`, GitHub release with the submission text
- README top: badges, one-line pitch, the 5 links

### Out of scope
- New features

## Design notes / decisions

- **One page means one page** — the mechanism section is the core; auth and
  framework choices get one line each. Link `ARCHITECTURE.md` for depth.
- **AI disclosure is graded** — be specific per area (directed vs decided), no
  blanket statements. Pull from each task's "AI: directed vs decided" block.
- **Verify links day-of** — free hosts sleep/expire; the submission includes the
  DB expiry date and a "if the instance is asleep, first call takes ~40s" note.

## Deliverables

- Final `docs/WRITEUP.md` (one page)
- `SUBMISSION.md`
- `docs/ARCHITECTURE.md` (optional)
- `docs/media/*`
- `v1.0` tag + release

## Acceptance criteria

- [ ] `WRITEUP.md` fits one page as PDF, all 6 sections present
- [ ] AI directed-vs-decided table has ≥ 8 specific rows aggregated from tasks
- [ ] `SUBMISSION.md` links all resolve; each verified same-day
- [ ] `./scripts/burst.sh <live-url>` → all PASS, captured in `evals/reports/`
- [ ] CI green on `main`; `v1.0` tagged
- [ ] Fresh clone → `docker compose up` → `burst.sh localhost` green (no tribal
      knowledge)

## Test plan

### Unit / Integration
| Test | Asserts |
|------|---------|
| `scripts/check-links.sh` | every URL in `docs/**` + README + SUBMISSION returns 2xx |
| full-suite CI | `mvn verify` + `evals.yml` green at the tagged commit |

### Eval scenarios
| Scenario | Runs against | Pass criteria |
|----------|--------------|---------------|
| all `EVAL-*` | deployed | green matrix in the committed final report |

## Definition of done

- [ ] All acceptance criteria met
- [ ] `SUBMISSION.md` sent
- [ ] Memory updated with live URL + submission date
- [ ] `v1.0` released

## AI: directed vs decided

- **Directed**: one-page discipline, per-area disclosure, day-of link verify.
- **Decided**: prose, media selection, release notes.
