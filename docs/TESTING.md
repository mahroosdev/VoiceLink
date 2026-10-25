# Testing

## Offline automated tests

The ordinary Maven suite uses fake provider adapters and does not call Groq, Gemini, or the email provider. The PostgreSQL profile starts an isolated Testcontainers database; do not point integration tests at production.

PowerShell commands from the repository root:

```powershell
.\mvnw.cmd -ntp test
.\mvnw.cmd -ntp clean package
.\mvnw.cmd -ntp -Ppostgres-it verify
node --test src/test/js/*.test.cjs
Get-ChildItem src/main/resources/static/js -File -Filter '*.js' | ForEach-Object { node --check $_.FullName }
```

The separate `provider-live` profile consumes provider quota and is not part of routine verification. It must never run without explicit operator authorization, verified account-tier/quota status, non-sensitive inputs, and the documented environment configuration.

## Previous automated evidence (before bounded retry and cleanup)

On 2026-10-25, against release candidate `363dc6b78f860e99563666473d66e713068df950`:

| Suite | Result | Scope |
| --- | ---: | --- |
| Java/Maven | 212 passed | Included 11 WebSocket tests; Linux Java 21/Maven 3.9.16 run, zero failures/errors/skips. |
| PostgreSQL/Testcontainers | 10 passed | PostgreSQL 17.11, Flyway V1–V8 and schema validation; zero failures/errors/skips. |
| JavaScript behavior | 55 passed | Node test runner; zero failures. |
| JavaScript syntax | 8 passed | `node --check` on production scripts. |

The Windows Maven run executed 212 tests but failed with 11 errors because Tomcat could not create its loopback selector pipe (`Invalid argument: connect`); these were environment errors, not passing WebSocket tests. During this cleanup, Linux/Maven 3.9.16 freshly ran `mvn -o -ntp package`: 212 tests, 0 failures/errors/skips, JAR built, `BUILD SUCCESS`. The PostgreSQL Failsafe integration goals then passed 10 tests, 0 failures/errors/skips, using an isolated PostgreSQL 17.11 Testcontainer, with Flyway V1–V8 and schema validation successful. The Testcontainers run used `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal` and `TESTCONTAINERS_RYUK_DISABLED=true`. JavaScript tests passed 55/55 behavior tests and 8/8 syntax checks. No provider-live request was made.

Reproducible Linux-container commands used for the fresh checks:

```powershell
docker run --rm -v "${PWD}:/workspace" -v voicelink-maven-cache:/root/.m2 -w /workspace maven:3.9.16-eclipse-temurin-21 mvn -o -ntp package
docker run --rm -e TESTCONTAINERS_RYUK_DISABLED=true -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -v "${PWD}:/workspace" -v voicelink-maven-cache:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock -w /workspace maven:3.9.16-eclipse-temurin-21 mvn -o -ntp -Ppostgres-it failsafe:integration-test failsafe:verify
```

The full `mvn -Ppostgres-it verify` lifecycle and `clean package` were not run during this cleanup. The normal Java suite plus package goal and the PostgreSQL Failsafe goals passed separately; no application source changed.

## Bounded retry and room-state cleanup verification

On 2026-10-25, verification was run against the uncommitted working tree on `release/standard-final`, starting at `363dc6b78f860e99563666473d66e713068df950`. Linux Java 21.0.12.1 and Maven 3.9.16 were used in an offline Maven container.

| Command/check | Result |
| --- | ---: |
| `mvn -o -ntp clean package` | 224 passed; 0 failures, 0 errors, 0 skipped; `BUILD SUCCESS`. |
| `mvn -o -ntp -Ppostgres-it verify` | 224 unit tests and 10 PostgreSQL integration tests passed; 0 failures, 0 errors, 0 skipped; `BUILD SUCCESS`. |
| `node --test src/test/js/*.test.cjs` | 56 passed; 0 failures. |
| Production JavaScript `node --check` | 8 passed; 0 failures. |
| `git diff --check` | Passed. |

The PostgreSQL profile used a disposable PostgreSQL 17.11 Testcontainer and successfully validated/applied Flyway V1–V8. It did not access production data. JavaScript behavior tests use local test infrastructure. No live Groq, Gemini, or email-provider calls were made. No graphical browser QA was performed.

The Java lifecycle commands were executed inside `maven:3.9.16-eclipse-temurin-21` with Maven offline mode. For the PostgreSQL run, the container was given the Docker socket plus `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal` and `TESTCONTAINERS_RYUK_DISABLED=true`. The exact Maven goals were `mvn -o -ntp clean package` and `mvn -o -ntp -Ppostgres-it verify`.

## Conversation UI and translation reliability follow-up

On 2026-10-25, the updated uncommitted working tree was reverified from HEAD `363dc6b78f860e99563666473d66e713068df950` on `release/standard-final` using Linux Java 21.0.12.1, Maven 3.9.16, and offline dependencies.

| Command/check | Result |
| --- | ---: |
| `mvn -o -ntp clean package` | 224 passed; 0 failures, 0 errors, 0 skipped; `BUILD SUCCESS`. |
| `mvn -o -ntp -Ppostgres-it verify` | 224 unit tests and 10 PostgreSQL integration tests passed; 0 failures, 0 errors, 0 skipped. |
| `node --test src/test/js/*.test.cjs` | 50 passed; 0 failures. |
| Production JavaScript `node --check` | 8 passed; 0 failures. |
| `git diff --check` | Passed. |

The PostgreSQL integration test used a disposable PostgreSQL 17.11 Testcontainer and applied Flyway V1–V8. This run made no live provider or email calls and did not access production data. No graphical browser verification was performed; the responsive layout and interactions still require owner review at desktop, 390px, and 320px. Offline translation tests verify request language, prompt construction, glossary handling, and structured responses; they do not establish recognition or translation quality. Live Standard provider output and human review remain outstanding.

## Production checks and limits

A read-only check recorded on 2026-10-25 found the Railway application healthy: `/actuator/health` returned HTTP 200/UP; the homepage, login, registration, CSS, and room JavaScript assets returned HTTP 200. Deployment logs showed Flyway V1–V8 and schema version 8 current. This dated check does not prove the service remains available today.

No live Groq/Gemini request, live translation-quality assessment, graphical two-person browser workflow, microphone capture, or end-to-end audio playback is established by the above results. Health checks and mocked adapter tests are not substitutes for those checks. For owner-run browser review, use [the two-browser QA procedure](MANUAL_TWO_BROWSER_QA.md). For provider review, first verify current account tiers, quota, and data terms; use [the Standard live QA plan](STANDARD_LIVE_QA_PLAN.md) only after authorization.

## 2026-10-25 final release-candidate verification

The current uncommitted release candidate at `363dc6b78f860e99563666473d66e713068df950` was checked with Linux Java 21.0.12.1 and Maven 3.9.16 in offline mode:

| Command/check | Result |
| --- | ---: |
| `mvn -o -ntp clean package` | 224 Java tests passed; 0 failures, 0 errors, 0 skipped; `BUILD SUCCESS`. |
| `mvn -o -ntp -Ppostgres-it verify` | 224 Java tests and 10 PostgreSQL/Testcontainers integration tests passed; 0 failures, 0 errors, 0 skipped; `BUILD SUCCESS`. |
| `node --test src/test/js/*.test.cjs` | 50 passed; 0 failures, 0 skipped. |
| Production JavaScript `node --check` | 8 passed; 0 failures. |
| `git diff --check` | Passed. |

The PostgreSQL profile used a disposable PostgreSQL 17.11 Testcontainer and applied Flyway V1–V8. It did not access the local development volume or production data. No Groq, Gemini, Premium, or email-provider calls were made. A separate Windows `mvnw test` run executed 224 tests with zero assertion failures but 11 environment errors in `RoomLiveWebSocketTests` because Tomcat could not establish a loopback connection; that Windows run failed. The complete Linux Java 21 suite and PostgreSQL profile above both passed.

A read-only request to the production health URL returned HTTP 200 with health status UP. GitHub's latest Railway-created production deployment record is for commit `ad07f55d150cb59bc182f3fd72349e660a1b4c17` and has status `success`; this is the previously deployed `origin/main`, not the current uncommitted release candidate. The Railway service's configured source branch and application service configuration were not independently readable through the available Railway access in this run, so this does not authorize or prove a deployment of the new release.

No graphical two-person browser QA, responsive visual QA, live Standard translation/audio quality review, or Premium validation was performed. Those checks remain pending and must not be described as passed.
