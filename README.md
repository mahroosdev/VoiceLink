# VoiceLink

**VoiceLink — Java based English↔Tamil speech and text translation for two person conversations**

VoiceLink helps two people who speak English and Tamil communicate in a private room. One participant speaks English and the other speaks Tamil. The application transcribes short speech turns, translates them, displays temporary captions, and generates translated audio. It also supports reviewed text translation. This repository is a controlled demonstration application; final owner browser and live provider validation remain pending, as recorded in [docs/TESTING.md](docs/TESTING.md).

## Who it serves

The intended users are two people who need a short bilingual conversation, including students or colleagues discussing technical work. Standard development is designed for Groq and Gemini free tier accounts; the operator must confirm the current account tiers before live calls. Use only synthetic or non-sensitive, non-confidential content while the free tier privacy and quota conditions apply.

## Architecture

- **Backend:** Java 21, Spring Boot 4.1.1, Spring MVC, Spring Security, Spring Data JPA, Bean Validation, and Maven Wrapper.
- **Frontend:** server rendered Thymeleaf, local CSS, vanilla JavaScript, browser MediaRecorder, and an authenticated raw WebSocket. There is no frontend framework, external font, or icon service.
- **Persistence:** PostgreSQL for accounts, room membership, room glossary, and other application records; Flyway V1–V8 on the integrated local release branch. Conversation transcripts, translated messages, and generated audio are not persisted.
- **Standard speech:** browser WebM/Opus upload → Groq `whisper-large-v3` transcription → Gemini `gemini-3.1-flash-lite` translation → temporary captions → Gemini `gemini-3.8-flash-lite-tts` voice → authenticated WAV playback.
- **Standard text:** explicit Total translation, or debounced Live translation with Private Preview by default. Shared Live requires consent. A reviewed result is sent explicitly.
- **Room context:** up to three recent eligible source turns, at most five minutes old and 1,200 Unicode code points combined, remain in memory. Each open room may store up to 12 glossary entries in PostgreSQL. Context is reference-only; glossary terms are used when they fit the current utterance.
- **Premium:** a separate designed provider mode using a separate billed Gemini project and credentials. It is disabled by default and has not been activated or live validated for this submission. There is no automatic Standard-to-Premium fallback.

## What is implemented

- Registration, session sign-in, account deletion, and a password reset feature that remains disabled unless configured by the operator.
- Two-person rooms with authenticated membership, manual invitation codes, a maximum of two participants, and server-assigned complementary English/Tamil directions.
- Per-conversation display names and a two-step invite preview on the integrated local release branch. The server derives Conversation Owner from the room creator's account ID; names never grant access.
- Temporary WebSocket room state, text messages, captions, and speech progress. Refresh or reconnect does not replay conversation history.
- Speak mode with a bounded recording, Live Send, and a private Finish & Send review. If recognition is wrong, the user can record again. The reviewed speech cannot be edited in this release.
- Type mode with Total, Live Private Preview, and explicitly enabled Shared Live. Final Send is explicit; provisional shared drafts are distinct from final messages.
- A room-specific technical glossary and bounded recent context. Closing a room clears context and deletes glossary entries.
- Temporary generated audio, accessible only to active room members, which expires after five minutes or earlier on eviction or restart.
- Safe user-facing provider failures and content-free operation diagnostics. Translation has a configurable ceiling of up to 15 seconds within the speech pipeline deadline, bounded transient retry, and a manual text Retry action.

## Security and privacy boundaries

Server-side session authentication, CSRF checks on forms and mutations, member-only room access, WebSocket membership checks, and deny-by-default route rules protect private surfaces. Passwords are hashed with Argon2id. Room owner status and language assignments are server-derived. User text is escaped in rendered HTML and inserted with DOM text APIs.

The application sends the current utterance and, when applicable, bounded context and glossary terms to the configured provider. It does not log audio, transcripts, translations, raw provider responses, prompts, or glossary content. Free tier quotas and provider data-use terms still apply. Registration/login and invite attempts do not yet have complete public-abuse throttling, so the application is intended for controlled demonstration rather than unrestricted public signup.

## Local setup

Prerequisites: JDK 21, Docker with Compose, and PowerShell on Windows. Maven itself need not be installed globally.

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
notepad .env
```

Use unique local PostgreSQL credentials in `.env`. The file is ignored by Git. Docker Compose reads it directly; the Spring process needs its values in the current Process environment:

```powershell
$settings = Get-Content .env | Where-Object { $_ -match '^(POSTGRES_USER|POSTGRES_PASSWORD|VOICELINK_[A-Z_]+)=' }
foreach ($setting in $settings) {
    $name, $value = $setting -split '=', 2
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}
$env:SPRING_PROFILES_ACTIVE = 'local'
docker compose --env-file .env up -d postgres
.\mvnw.cmd -ntp spring-boot:run
```

Open `http://localhost:8080/` and `http://localhost:8080/actuator/health`. The local database runs on port 5432. See [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) for the controlled Railway configuration. Never copy local or production secrets into source control, browser fields, screenshots, or issue reports.

Standard speech and translation require `VOICELINK_GROQ_API_KEY`, `VOICELINK_GEMINI_API_KEY`, and `VOICELINK_STANDARD_FREE_TIER_CONFIRMED=true`. Set the confirmation flag only after checking the actual Groq and Gemini project tiers in their consoles. A key alone does not establish billing tier. Premium remains disabled with `VOICELINK_PREMIUM_ENABLED=false`.

## Verification

```powershell
.\mvnw.cmd -ntp test
.\mvnw.cmd -ntp clean package
.\mvnw.cmd -ntp -Ppostgres-it verify
```

Normal tests use fake providers and never call Groq, Gemini, or an email service. The PostgreSQL profile uses Docker/Testcontainers. JavaScript behavior tests run with `node --test` over the `.test.cjs` files in `src/test/js`; check production scripts with `node --check`. The final integrated counts and exact environment are recorded in [docs/TESTING.md](docs/TESTING.md). The `provider-live` Maven profile is separate and consumes free tier quota; do not run it as part of routine verification.

The final local candidate passed 209 Java tests (including 11 real WebSocket tests), 10 PostgreSQL integrations, 47 JavaScript behavior tests, and seven syntax checks. A separate local run with disabled providers passed 34 synthetic HTTP/session/WebSocket checks across three isolated sessions. These are automated and protocol-level results; authenticated graphical two-browser QA, true small-screen interaction, live English↔Tamil quality, and deployed behavior remain pending. See the [campus submission checklist](docs/CAMPUS_SUBMISSION_CHECKLIST.md), [manual two-browser worksheet](docs/MANUAL_TWO_BROWSER_QA.md), and [non-executing Railway security runbook](docs/RAILWAY_SECURITY_RELEASE_RUNBOOK.md) for the demonstration order and remaining gates.

## Demonstration

1. Open the public homepage, then register or sign in with two separate accounts in two browser sessions.
2. Create a **Standard** conversation, choose a per-conversation name and the creator's speaking language, and share the private invite code directly with the other account.
3. Enter the invite code with the second account, verify the owner and complementary language preview, select the available speaking language, and choose the second conversation name.
4. Confirm both participants see their own **You** identity, the other person's name, and the correct speaking/receiving languages.
5. In **Type**, use **Quick chat** to exchange untranslated temporary messages and switch the single timeline between All messages, Translated, and Quick chat. Use **Translate message** or **Live translation** to review and explicitly send a translation. Opt into Shared Live only when both participants agree to provisional drafts.
6. In **Speak**, record a short synthetic utterance, review recognized and translated captions, send it, and play the temporary audio. Repeat in the opposite language.
7. Try a room glossary term, then close the room and confirm the glossary is removed. No permanent conversation history should appear after refresh.

Use non-sensitive example phrases and monitor free tier quotas. Live provider quality, Tamil recognition, voice pronunciation, browser accessibility, and deployed two-user behavior require human review; offline tests cannot prove them.

## Known limitations and next work

- English↔Tamil is the only supported pair. Actual conversational quality, code-switched pronunciation, and latency vary by provider and remain dependent on live review.
- Recognition errors can be handled by recording again; transcript editing is not implemented.
- Captions, messages, context, and generated audio are temporary and are not replayed after reconnect. Audio is not retained for three hours and text is not retained for six hours.
- Waiting-room closure belongs to the creator; either member can close an active room. Separate Leave, owner-only Close, member removal, and ownership transfer are deferred.
- Complete public-abuse controls, email verification, broader browser/accessibility QA, and multi-instance shared rate accounting remain future hardening work.
- Premium activation, billing, quality, latency, and paid-provider validation are deferred. The Standard release must not call Premium automatically.

Further detail: [architecture and component diagram](docs/ARCHITECTURE.md), [API reference](docs/API_REFERENCE.md), [development plan](docs/DEVELOPMENT_PLAN.md), [provider evaluation](docs/AI_PROVIDER_EVALUATION.md), [Standard live QA plan](docs/STANDARD_LIVE_QA_PLAN.md), [testing evidence](docs/TESTING.md), [deployment](docs/DEPLOYMENT.md), [campus submission checklist](docs/CAMPUS_SUBMISSION_CHECKLIST.md), and [project log](docs/PROJECT_LOG.md).
