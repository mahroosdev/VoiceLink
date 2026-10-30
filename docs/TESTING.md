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

## 2026-10-30 security follow-up verification (working tree based on d21d13a05f918a064ed53b42b18d5a8f30e5a4c1)

This verification covered a temporary source snapshot of the current uncommitted working tree based on the commit above. The source archive excluded .git, build output, environment files, and credentials; hashes for all 11 modified or added source/documentation files matched the worktree before the run. No application files were modified while Maven verification ran.

| Command/check | Result | Scope |
| --- | ---: | --- |
| Focused Linux Maven regression command: mvn -o -ntp -Dtest=EndpointAbuseRateLimiterTests,PasswordHashConcurrencyGateTests,LiveInvalidationAbuseFlowTests,TextTranslationFlowTests,TextSharingStateTests test | 30 passed; 0 failures, 0 errors, 0 skipped | Endpoint-abuse limits, password-hash concurrency gate, Live invalidation flow, translation and sharing state. |
| Full Linux Maven/PostgreSQL lifecycle: mvn -o -ntp -Ppostgres-it verify | 262 Java tests and 10 PostgreSQL integration tests passed; 0 failures, 0 errors, 0 skipped; BUILD SUCCESS | Linux Java 21 / Maven 3.9.16. Linux WebSocket tests passed. PostgreSQL 17.11 ran in a disposable Testcontainers database. |
| Flyway | V1–V9 validated and applied successfully | PostgreSQL/Testcontainers integration run. |
| Packaging | Passed | The full verify lifecycle compiled and packaged the application JAR successfully. |
| node --test src/test/js/*.test.cjs | 56 passed; 0 failures, 0 skipped | JavaScript behavior tests. |
| Production JavaScript node --check | 8 passed | All eight production JavaScript files. |
| git diff --check | Passed | Final working-tree diff, including this entry. |

The prior Windows Maven run remains **incomplete and failed**: 255 tests were reported with 0 assertion failures, 12 errors, and 0 skipped; the errors were embedded Tomcat loopback startup failures. The successful Linux run above does not convert that Windows run into a pass.

At the time of this source-verification entry, local HTTP startup had not yet been tested; see the later 2026-10-30 HTTP smoke follow-up below. Graphical browser, authenticated two-user browser/WebSocket, production proxy, backup/restore, Railway deployment, and live provider behavior remained unverified. No Groq, Gemini, email provider, Premium feature, or production database was used. PostgreSQL integration used an isolated disposable test database; the persistent development database was left untouched.

## 2026-10-30 local runtime HTTP smoke follow-up

A later smoke pass used the documented local Spring profile in the existing Linux Maven container, with Groq, Gemini, email, password reset, and Premium disabled. The app source snapshot matched the current pending application-code changes; this note records runtime evidence, not a new commit.

The database was a separate PostgreSQL 17.11 container bound to 127.0.0.1:55439, using tmpfs for its data directory and no persistent volume mounts. The app log identified that database, and Flyway validated and applied V1–V9. The health endpoint returned HTTP 200 with status UP.

HTTP-only checks returned 200 for the homepage, About, login, registration, CSS, password-visibility JavaScript, and favicon. The password-reset page returned 404 because password reset was explicitly disabled, matching the application's disabled-feature response. Unauthenticated room and live-state requests redirected to login; a login POST without CSRF returned 403. A protected room-creation script redirected when unauthenticated and returned 200 when authenticated.

Two separate cookie-backed HTTP sessions registered and logged in temporary accounts, created a Standard room, previewed the invite, joined through setup, and loaded the resulting room page. This confirms the HTTP account/invitation flow and authenticated page responses; it did not assert rendered participant-name content, exercise a graphical browser, or test WebSocket delivery, live draft behavior, reconnection, or message ordering.

The documented Windows Maven startup also reached the disposable database and applied V1–V9, but embedded Tomcat failed before listening with the Windows loopback selector error: “Unable to establish loopback connection” / “Invalid argument: connect.” Running the app inside the existing Linux container avoided that environment-specific startup failure.

The temporary application and database containers were stopped and removed after the HTTP checks. The smoke pass reported the existing development PostgreSQL container and persistent voicelink_postgres_data volume unchanged. No provider request was made. This follow-up contains no graphical browser, WebSocket, provider, production, Railway, proxy, backup, or restore verification. The current pass did not repeat the runtime smoke; Docker Desktop's Linux engine was unavailable, so graphical UI checks could not be run.

## 2026-10-30 production-observation follow-up

This entry records Railway observations supplied for this review. No Railway settings, production database, environment-variable values, or secrets were queried during this pass.

- The supplied deployment record identifies production commit `6dc9668`; the supplied health and migration evidence reports the application healthy and Flyway V9 current. This is evidence for that observed deployment, not a check of the current live service in this pass.
- Production logs dated October 27 reportedly show repeated `OutOfMemoryError: Java heap space` failures during Argon2 registration hashing, along with HTTP 499 registration requests. In the reviewed source, `SecurityConfig` retains Argon2id parameters of 65,536 KiB, three iterations, one lane, and a 32-byte output. The pending `PasswordHashConcurrencyGate` uses a non-blocking process-local semaphore, default limit one and validated maximum four. The endpoint filter holds a permit around POST `/register`, `/login`, `/account/delete`, and POST `/reset-password` when password reset is enabled; its `finally` releases the permit after normal completion or an exception. This bounds simultaneous password-hash work per process without weakening Argon2 parameters. It reduces the code-level unbounded-concurrency risk, but does not prove that the default or configured limit fits Railway's actual heap, nor establish that every historical 499 had this cause. Validation under the actual Railway memory limit remains outstanding. The repository tree for supplied production commit `6dc966817105bf99a222d3ad053d8303f4f57354` was also inspected locally and does not contain `PasswordHashConcurrencyGate.java`; this pending mitigation is not present in that deployed commit.
- Password-reset delivery warnings are consistent with the notifier reporting a failed HTTP response or transport/dispatch error; the service then removes the matching reset token and logs a generic warning. The source does not expose provider response bodies or secrets in those warnings. The feature is disabled by default. When enabled, the app requires `VOICELINK_PASSWORD_RESET_ENABLED`, `VOICELINK_PUBLIC_BASE_URL`, `VOICELINK_RESET_MAIL_API_KEY`, and `VOICELINK_RESET_MAIL_FROM_ADDRESS`. The reported warnings alone do not distinguish a code defect from provider credentials, sender/domain approval, recipient policy, or network configuration. Successful delivery has not been independently verified; no email-provider request was made for this review.
- The deployment documentation and supplied current observation describe a single application instance. Endpoint quotas and the password-hash gate are process-local, not distributed controls.
- Production PostgreSQL was reported as using persistent storage. The supplied evidence available for this entry does not include the production volume identifier or a current used/capacity measurement. Neither was independently queried; exact volume identity and current storage usage remain unverified.
- The production profile uses Tomcat's native forwarded-header handling, while `EndpointAbuseFilter` obtains the address through `HttpServletRequest.getRemoteAddr()` and does not parse client-supplied forwarded headers itself. Whether Railway's actual proxy address is trusted by the deployed Tomcat configuration, and whether distinct clients receive distinct servlet remote addresses, remains an operational verification requirement. Do not treat per-client rate enforcement as proven until checked through trusted Railway ingress.
- A backup/restore exercise has not been verified. A persistent volume or successful snapshot alone is not evidence of a tested restore.

These production observations are separate from the local Linux/Maven, PostgreSQL/Testcontainers, JavaScript, and HTTP smoke results recorded above. They do not establish graphical browser or authenticated two-user WebSocket behavior, live translation/audio quality, password-reset email delivery, production proxy-address handling, backup restoration, or current Railway configuration beyond the observations explicitly listed here.

## 2026-10-30 Java heap-budget estimate and gate adjustment

A local Java 21.0.11 HotSpot runtime was used to inspect Java ergonomics without starting Docker or the application. With `-XX:MaxRAM=1000000000`, `MaxRAMPercentage` remained its default 25% and `MaxHeapSize` was 251,658,240 bytes (240 MiB). With `-XX:MaxRAM=1g` (1 GiB), the corresponding maximum was 268,435,456 bytes (256 MiB). These are JVM ergonomics simulations for the stated limits, not a measurement of Railway's effective flags or live heap.

The configured Argon2id memory cost of 65,536 KiB is 64 MiB of algorithm working memory per operation, not the complete Java heap cost of a request. Two simultaneous operations therefore represent at least 128 MiB of Argon2 working memory, before baseline application heap and per-operation overhead; at a 240 MiB maximum that leaves at most 112 MiB for those other heap uses. A single operation represents 64 MiB before overhead. Production heap occupancy during registration was not supplied, so this estimate cannot establish the exact margin or prove that one concurrent hash cannot exhaust the heap.

Based on the historical heap OOMs and the estimated 240–256 MiB heap, the local default for `VOICELINK_ABUSE_PASSWORD_HASH_MAX_CONCURRENT` was lowered from two to one in `application.yml` and the constructor fallback. Argon2 parameters are unchanged. The configurable upper bound remains four; do not set a higher override without a measured heap budget. The actual Railway override is unknown, and the observed production commit `6dc966817105bf99a222d3ad053d8303f4f57354` does not contain the gate at all, so this pending local adjustment is not active in production.

`java -XX:MaxRAM=1000000000 -XX:+PrintFlagsFinal -version` and `java -XX:MaxRAM=1g -XX:+PrintFlagsFinal -version` produced the estimates above. The repository's checked-in source/configuration has no `-Xmx`, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS`, or `MaxRAMPercentage` override. No matching override variable was present in this local shell; this says nothing about Railway's service variables.

Focused Windows Java test after the local adjustment: `.\mvnw.cmd -o -ntp '-Dtest=PasswordHashConcurrencyGateTests' test` — 7 passed, 0 failures, 0 errors, 0 skipped. Coverage includes one-permit saturation/recovery, latch-coordinated concurrent acquisition, existing multi-permit capacity, invalid configuration, generic 429 behavior, and permit release after both a handler exception and an interrupted handler completion. The previously recorded Linux full suite (262 Java and 10 PostgreSQL integration tests) predates this default change. Docker's Linux engine was unavailable on this pass, so the full suite was not rerun against the changed source.
