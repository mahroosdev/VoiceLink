# VoiceLink campus submission checklist

## Local candidate and evidence

- [x] Keep `main` at the tested J4/J4.1 release checkpoint until its separate production deployment is authorized and verified.
- [x] Keep the integrated J5 Standard candidate on `release/standard-final`, with J5A/J5A.1 and J5B1 in its ancestry.
- [x] Build with Java 21; pass 209 normal Java tests, 10 PostgreSQL integrations, 47 JavaScript behavior tests, and seven production syntax checks. The 11 real WebSocket tests are included in the 209.
- [x] Verify Flyway V1–V8 on disposable PostgreSQL 17.11 and preservation of a synthetic pre-V8 participant row.
- [x] Run a local app with Standard and Premium provider calls disabled. Complete 34 HTTP/session/WebSocket checks with synthetic accounts and messages.
- [x] Verify the illustrative homepage text and inspect 1440 px and 700 px local renders.
- [ ] Complete final authenticated two-browser visual QA, including 390 px and 320 px device widths, keyboard focus, reduced motion, and screen-reader checks.
- [ ] Complete owner-authorized, free-tier Standard English↔Tamil speech/text, caption, and translated-audio review using only non-sensitive examples. Record direction, outcome, and timing without private content or keys.
- [ ] Confirm Railway credential review, current source, exact deployed commit, health, PostgreSQL/Flyway state, assets, and two-person production behavior after the staged releases.
- [ ] Complete and sign off the [two-browser worksheet](MANUAL_TWO_BROWSER_QA.md), with separate results for 1440×900, 1024×768, 390×844, and 320×800 CSS viewports.
- [ ] Capture the redacted screenshot set in the worksheet: homepage preview, room setup, WAITING/ACTIVE perspectives, Type/Speak controls, safe error, privacy/reconnect, and closure. Record actual viewport and browser version; omit private content and invite codes.
- [ ] Assemble training/evaluation evidence required by the campus rubric. VoiceLink uses pretrained Groq/Gemini services and performs **no local model training or fine-tuning**; label custom-training logs/datasets **not applicable**. Cite provider/model documentation, the natural-conversation prompt contract, offline tests, and later human bilingual evaluation separately. Do not invent training metrics or present mocked outputs as quality evidence.
- [ ] Obtain the owner's **Cloud Computing training** completion certificate or official completion evidence. The owner supplies the original; record its issuing organization and completion date outside the public repository if personally identifying.
- [ ] Obtain the owner's **Java Developer to AI Engineer training** completion certificate or official completion evidence. The owner supplies the original; do not claim completion until received and checked.

Checked items are verified by current local evidence in [TESTING.md](TESTING.md). They are not a claim that the deployed application or live translation quality is ready for submission. Keep Premium disabled and untested.

## Submission evidence map

| Requirement | Evidence to cite | Status |
| --- | --- | --- |
| Architecture, Java backend, REST and authenticated WebSocket | [ARCHITECTURE.md](ARCHITECTURE.md), [README.md](../README.md) | Implemented; local automated/protocol verification |
| Local setup and PostgreSQL/Flyway V1–V8 | [README.md](../README.md), [TESTING.md](TESTING.md) | Local verification passed; production V8 pending |
| Railway deployment and recovery | [DEPLOYMENT.md](DEPLOYMENT.md), [security runbook](RAILWAY_SECURITY_RELEASE_RUNBOOK.md) | Prepared; staged release and rotation unexecuted |
| Test counts, known limitations, graphical QA | [TESTING.md](TESTING.md), [two-browser worksheet](MANUAL_TWO_BROWSER_QA.md) | Automated evidence recorded; owner browser QA pending |
| Screenshots and model training/evaluation evidence | [Two-browser screenshot checklist](MANUAL_TWO_BROWSER_QA.md), [Standard live QA plan](STANDARD_LIVE_QA_PLAN.md), and [provider evaluation](AI_PROVIDER_EVALUATION.md) | Redacted captures and human bilingual review pending; custom training not applicable |

## Demonstration sequence

1. Introduce the problem: two people speaking English and Tamil need a private, short conversation with temporary text and speech translation. Show the illustrative homepage preview and explain that it does not call AI.
2. Show the Java 21/Spring Boot backend structure: authentication and room controllers, `SpeechTurnService` and `TextTranslationService`, provider interfaces, and safe failure mapping. Explain that only the supported English↔Tamil direction is selected by the server.
3. Show PostgreSQL and Flyway V1–V8. Explain stored accounts, room membership, immutable profile, per-room terminology, and V8 participant names. Explain that conversation messages, recent context, and generated audio are temporary rather than PostgreSQL history.
4. Use two separate signed-in accounts. Create a Standard room with an English-speaking owner and per-conversation name; share its private code. Join as the Tamil-speaking participant after the preview, select the available language, and confirm complementary language labels and server-derived owner identity.
5. Demonstrate authenticated WebSocket room activation and two-way Quick chat delivery. Point out You/Partner attribution, one temporary timeline with All/Translated/Quick chat filters, independent history scrolling, and that a third account cannot enter the room.
6. Explain Type modes: Translate message, Live translation with Private Live or explicit Shared Live, and Quick chat. Explain private review, explicit final Send, safe Retry, and provisional versus final messages. With providers disabled, show the safe failure instead of claiming a translation occurred.
7. Explain Speak modes: Live Send and private Finish & Send, bounded WebM/Opus upload, Groq transcription, Gemini structured translation, temporary captions, Gemini TTS, authenticated audio, and Record again when recognition is wrong. Demonstrate actual speech/voice only after separate owner authorization and verified free-tier configuration.
8. Close the room and show that the saved room glossary is removed. End with test counts, privacy limits, Premium deferred status, and the remaining live/mobile/deployment checks.

## Files to submit or cite

- [README.md](../README.md): project overview, local setup, workflow, security boundaries, and limitations.
- [ARCHITECTURE.md](ARCHITECTURE.md): Java/Spring, PostgreSQL/Flyway, WebSocket, Standard provider, context, glossary, and temporary-state design.
- [TESTING.md](TESTING.md): exact automated and local QA evidence, failures resolved in the test environment, and unverified items.
- [DEPLOYMENT.md](DEPLOYMENT.md): staged J4/J4.1-first deployment and later owner-approved integrated J5 release.
- [AI_PROVIDER_EVALUATION.md](AI_PROVIDER_EVALUATION.md): provider choice and live-quality validation scope.
- [MANUAL_TWO_BROWSER_QA.md](MANUAL_TWO_BROWSER_QA.md): exact graphical QA sequence, viewport and screenshot evidence checklist.
- [RAILWAY_SECURITY_RELEASE_RUNBOOK.md](RAILWAY_SECURITY_RELEASE_RUNBOOK.md): non-executing backup, credential, staged release, health, and rollback plan.
- [API_REFERENCE.md](API_REFERENCE.md): authenticated REST/form endpoints and raw WebSocket v1 contract.
- [STANDARD_LIVE_QA_PLAN.md](STANDARD_LIVE_QA_PLAN.md): bounded synthetic provider QA; execution requires separate authorization.

Do not include `.env`, API keys, private transcripts, real audio, or screenshots containing personal data. Use synthetic accounts and examples in the campus demonstration.
