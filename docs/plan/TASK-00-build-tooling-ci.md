# TASK-00 — Build, tooling & CI baseline

| | |
|---|---|
| **Status** | Not started |
| **Branch** | `task/00-build-tooling-ci` |
| **Depends on** | — |
| **Invariant(s)** | — |
| **Est. effort** | ~1.5h |

## Context

The scaffold compiles conceptually but has no Maven wrapper, no verified build,
and CI has never run. Every later task needs a trustworthy `mvn verify` and a
reproducible container build. Testcontainers is the backbone of the integration
suite, so Docker-in-CI must work.

## Goal

`git clone` → `./mvnw verify` passes on a clean machine with only a JDK and
Docker; CI is green on `main`; `docker build` produces a runnable image.

## Scope

### In scope
- `mvnw` / `mvnw.cmd` + `.mvn/wrapper/` committed (`mvn wrapper:wrapper -Dmaven=3.9.9`)
- `.github/workflows/ci.yml`: `./mvnw -B verify` + `docker build`, Java 21 temurin, maven cache
- A trivial `WalletApplicationTest` context-load test so `verify` exercises Spring wiring
- `spring-boot-maven-plugin` build-info + reproducible layer jar
- `docs/plan/` linked from top-level `README.md`
- `.editorconfig`, license header policy decision (skip headers — note it)

### Out of scope
- Any domain logic (TASK-01+)
- Deploy pipeline (TASK-11)

## Design notes / decisions

- **Maven wrapper over "assume mvn installed"** — CI and the Dockerfile both use
  a pinned version; no "works on my machine".
- **Testcontainers over an embedded/H2 DB** — the invariants depend on Postgres
  semantics (`FOR UPDATE`, `ON CONFLICT`, serializable). H2 would pass tests that
  production fails. Cost: CI needs Docker (GitHub-hosted runners have it).
- Keep the Dockerfile build stage on the `maven:3.9-eclipse-temurin-21` image
  rather than copying the wrapper — simpler, layer-cached `go-offline`.

## Deliverables

- `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`
- `src/test/java/com/paytm/wallet/WalletApplicationTest.java`
- Updated `.github/workflows/ci.yml`
- `.editorconfig`
- `README.md` badge + "Delivery plan" link

## Acceptance criteria

- [ ] `./mvnw -B verify` green on a machine with no global Maven
- [ ] CI run on `main` is green and visible publicly
- [ ] `docker build -t paytm-wallet:dev .` succeeds; `docker run` boots and fails
      health only for lack of DB (not classpath/JVM errors)
- [ ] `./mvnw -q dependency:tree` has no `SNAPSHOT`s

## Test plan

### Unit
| Test | Asserts |
|------|---------|
| `WalletApplicationTest.contextLoads` | Spring context starts with test profile + Testcontainers Postgres |

### Integration
| Test | Asserts | Engines |
|------|---------|---------|
| — | (none yet) | n/a |

### Eval scenarios
| Scenario | Runs against | Pass criteria |
|----------|--------------|---------------|
| `EVAL-O2` (partial) | local | `docker compose up` builds the image without error |

## Definition of done

- [ ] Acceptance criteria met
- [ ] CI green, link captured in `README.md`
- [ ] Branch squash-merged, status Done

## AI: directed vs decided

- **Directed**: wrapper + Testcontainers-in-CI choice, pinned versions.
- **Decided**: exact GitHub Actions YAML, `.editorconfig` contents.
