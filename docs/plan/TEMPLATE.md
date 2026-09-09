# TASK-NN — <title>

| | |
|---|---|
| **Status** | Not started \| In progress \| Blocked \| Done |
| **Branch** | `task/NN-short-slug` |
| **Depends on** | TASK-xx, TASK-yy |
| **Invariant(s)** | — \| #1 Conservation \| #2 No overdraft \| #3 Exactly-once \| #4 Race-free get-or-create |
| **Est. effort** | ~Nh |

## Context

Why this task exists. What in the problem statement it maps to. What is already
in place from earlier tasks.

## Goal

One or two sentences. The observable end state.

## Scope

### In scope
- ...

### Out of scope
- ... (and which later task owns it)

## Design notes / decisions

The decisions made here, with rationale and rejected alternatives. This is the
raw material for `docs/WRITEUP.md` — write it as if defending it in a review.

## Deliverables

- Code: `path/to/File.java` — responsibility
- Migration: `Vn__x.sql`
- Docs: which `WRITEUP.md` section this fills
- Eval: `evals/scenarios/EVAL-xx.md`

## Acceptance criteria

Checkable statements. "Given X, when Y, then Z."

- [ ] ...

## Test plan

### Unit
| Test | Asserts |
|------|---------|
| ... | ... |

### Integration (`InvariantsIT` / `*IT`)
| Test | Asserts | Engines |
|------|---------|---------|
| ... | ... | all 3 / n/a |

### Eval scenarios
| Scenario | Runs against | Pass criteria |
|----------|--------------|---------------|
| `EVAL-xx` | local + deployed | ... |

## Definition of done

- [ ] Acceptance criteria met
- [ ] Unit + integration tests green in CI
- [ ] Eval scenario documented and passing
- [ ] Relevant `WRITEUP.md` section updated
- [ ] Branch squash-merged, task status set to Done

## AI: directed vs decided

- **Directed** (I chose the approach, AI implemented): ...
- **Decided** (I accepted AI's design): ...
