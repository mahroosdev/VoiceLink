# VoiceLink development plan

Each gate requires its own scope and review before implementation. This document records direction, not authorization to build future features.

| Gate | Scope | Status |
| --- | --- | --- |
| 0 | Verify workspace and local toolchain | PASS |
| 1 | Application foundation, configuration, local database definition, minimal security/page, tests, docs | PASS; includes owner-performed PostgreSQL/runtime verification |
| 1.5 | Record evidence and publish a public GitHub foundation checkpoint | PASS; public `main` push and remote verification completed |
| 2A | Account, preferences, session security, and migration plan | PASS / APPROVED by owner |
| 2B | Implement accounts, preferences, registration, session login/logout, and verification | PASS; fast tests, clean package, and PostgreSQL 17.11 Testcontainers integration verified by Codex |
| 3A | Two-person room and membership architecture review | PASS / APPROVED by owner |
| 3B | Implement two-person rooms, membership, and minimal browser flow | PASS; 25 fast tests, clean package, and 3 PostgreSQL integration tests passed |
| 4A | Live communication and WebSocket architecture review | PASS / APPROVED by owner |
| 4B | Live WebSocket text/event transport and verification | Automated verification passed; owner integrated browser verification pending, intentionally deferred to a later end-to-end application test |
| 5A | Speech/AI provider validation and architecture | PASS / APPROVED by owner; provisional stacks recorded in AI_PROVIDER_EVALUATION.md |
| 5B | Standard/free-tier STT, translation, and TTS foundation | Initial Azure-backed checkpoint passed 49 fast tests and 3 PostgreSQL integrations. Standard translation/TTS then revised to Gemini Free before live validation; 53 fast tests, clean package, and 3 PostgreSQL integrations passed. Owner live English/Tamil and integrated browser checks remain pending. |
| 6A | Bounded context and room glossary architecture | PASS / APPROVED by owner |
| 6B | Implement bounded in-memory context and room-scoped PostgreSQL glossary | PASS; 69 offline tests, clean package, and 4 PostgreSQL 17.11 integration tests passed in isolated Linux verification; live/manual quality pending |
| 6C | Context/glossary UX, operational hardening, and release checks | PASS; 71 offline tests, clean package, and 4 PostgreSQL 17.11 integration tests passed in isolated Linux verification; live/manual quality pending |
| 7A | Premium profile architecture and current provider review | PASS / APPROVED by owner; architecture recorded below |
| 7B | Implement explicit room-scoped Premium selection and paid Gemini translation/TTS with shared Groq STT | PASS by current offline evidence: 86 fast tests, clean package, and 5 PostgreSQL 17.11 integrations; live/manual quality pending |
| 8A | Professional UI/UX architecture | PASS / APPROVED by owner; architecture recorded below |
| 8B | Professional UI implementation and verification | PASS by current automated evidence: 89 offline tests, clean package, and 5 PostgreSQL 17.11 integrations; manual visual/browser QA pending |
| 9A | Owner browser, responsive, accessibility, two-user, and live-provider QA | PARTIAL / DEFERRED by owner: first three manual UI checks reported PASS; remaining checks move to final integrated QA |
| 10A | Railway production deployment readiness in repository | PASS by offline evidence: 93 fast tests, clean package, and 5 PostgreSQL 17.11 integrations; no cloud resources or provider calls |
| 10B | Owner-controlled Railway deployment | NOT STARTED; requires separate authorization and actual account/billing/network review |
| Final integrated QA | Complete deferred browser, two-user, microphone, English↔Tamil Standard provider, and deployed smoke checks | NOT STARTED |
| H | Advanced speech feasibility and Premium cost-control architecture audit | COMPLETE; progressive speech deferred and working baseline preserved |
| I | Operational Premium profile and room-scoped API Cost Saver | IMPLEMENTED OFFLINE; paid live validation and production activation pending |
| J | Full application QA, documentation, and campus submission | NOT STARTED |

Later gate boundaries may be revised during planning. No paid AI service or production deployment is part of Gate 1.

Evidence is recorded in [the project log](PROJECT_LOG.md), [testing record](TESTING.md), and [provider evaluation](AI_PROVIDER_EVALUATION.md). Gate 3A was accepted before Gate 3B implementation, Gate 4A before Gate 4B, Gate 5A before Gate 5B, and Gate 6A before Gate 6B. Gate 6B and Gate 6C received separate implementation authorization and passed automated verification. Gate 7A was approved; the separately authorized Gate 7B implementation passed offline verification. Gate 8A was approved and Gate 8B separately authorized; Gate 8B passed current automated verification. The Gate 4B owner integrated browser check, Gate 8B manual visual/browser QA, and live-provider/Tamil quality checks remain pending.

## Gate 6A approved boundaries

Conversation context stays in memory and is never stored as transcript/history in PostgreSQL. A translation may reference at most the newest three eligible prior turns, each no older than five minutes and no longer than 400 Unicode code points, with a combined maximum of 1,200 Unicode code points. Context is reference-only; Gemini translates only the current utterance. Context is lost on application restart and cleared when the room closes.

The technical glossary is specific to a room and persisted in PostgreSQL through the approved V3 `room_glossary_entries` migration. A room may have at most 12 entries. Entries survive browser refresh, reconnect, and application restart while the room is open; explicit room closure deletes them. Only authenticated room members may access them. Source terms are 1–48 Unicode code points and preferred terms are 1–64; a blank preferred term in the UI explicitly means preserve the source term. Normalize terms with NFC, trim and collapse whitespace, and use locale-independent lowercase lookup for uniqueness. Match Latin terms case-insensitively as whole phrases, with longer terms taking precedence when matches overlap. Edits use optimistic `row_version` handling.

Context and glossary data may be sent to Gemini Free Tier. Standard/free development remains limited to non-sensitive, non-confidential conversations. Do not log context, glossary contents, raw Gemini prompts, provider bodies, transcripts, translations, or audio. Gate 6B implemented these approved boundaries; actual live translation quality remains unverified.

## Gate 7A approved Premium architecture

STANDARD remains Groq `whisper-large-v3` speech recognition → Gemini `gemini-3.1-flash-lite` translation → Gemini `gemini-3.8-flash-lite-tts` speech. PREMIUM uses the same Groq `whisper-large-v3` recognition → paid Gemini `gemini-3.8-flash` translation → paid Gemini `gemini-3.8-flash-tts` speech with the documented `Kore` voice, subject to checking the current official API during implementation. Google Cloud STT and Chirp remain deferred. Premium translation retains Natural Conversational behavior, explicit English↔Tamil direction, bounded context, room glossary, and a structured translation result. Actual Premium English↔Tamil quality and latency remain unverified until later human/live testing.

Premium uses a separate billed Gemini project and API key from Standard and is disabled by default with `VOICELINK_PREMIUM_ENABLED=false`. A paid call requires a stored PREMIUM room profile, server-side enablement, and configured Premium credentials. There is no automatic fallback between profiles. The creator selects STANDARD or PREMIUM when creating a room; the choice is immutable and visible to both members. Gate 7B uses an `AiServiceProfile`/provider-bundle resolver with one shared speech, context, and glossary pipeline rather than duplicating `SpeechTurnService`.

Flyway V4 adds `service_profile` to `conversation_rooms`, defaults existing rooms to STANDARD, and constrains values to STANDARD or PREMIUM. The dedicated Gate 7B authorization was granted and its offline implementation passed. Paid project creation and live/paid Premium validation remain deferred until later integrated testing.

Gate I adds the immutable room-level API Cost Saver choice through Flyway V7. It defaults to ON, applies only to Premium rooms, and is visible to both participants. ON uses a 1,500 ms browser and server paid-preview interval; OFF uses 800 ms. Both modes retain the same Premium models, translation quality policy, hard paid-operation limits, idempotency, and no-fallback rule. Content-free instance-local request and provider-token metrics distinguish translation and TTS. These controls do not prove actual charges or guarantee a spending cap, and they require a shared store before unrestricted multi-replica use. Premium remains disabled in production pending separately authorized billed-key setup and limited live validation.

## Gate 8A approved Professional UI/UX architecture

The submission release uses a light-only warm-white, deep-ink, and teal visual system with a restrained violet Premium accent, system/local fonts, and a minimal custom VoiceLink brand treatment. The frontend remains Thymeleaf, HTML, CSS, and vanilla JavaScript, with no frontend framework, build tooling, or external icon/font dependency. The active conversation prioritizes connection state, recording and processing, temporary live captions, and translated audio. The room glossary is a collapsible secondary side panel on desktop and an in-flow disclosure on mobile. Standard/Premium descriptions and concise privacy wording must remain technically accurate; recent captions must not imply persistent chat history.

Gate 8B supports desktop, tablet, and mobile layouts down to 320px in source, with keyboard access, visible focus, contrast-oriented tokens, status and error feedback, touch-friendly controls, and reduced-motion handling. Static checks do not establish actual appearance or accessibility across browsers. Gate 8B does not add transcript persistence or expand backend, schema, or provider behavior for visual polish. The only presentation-support backend change allows public GET access to the exact new stylesheet through `SecurityConfig`. Gate 8B received dedicated authorization and was implemented. Final manual visual/browser QA remains pending.

## Gate 10A deployment readiness boundary

The approved deployment target is one Railway native Java 21/Spring Boot service plus one private PostgreSQL service. Gate 10A prepares common `PORT` support, explicit JDBC database environment mapping, and a small `prod` profile for trusted forwarded headers and secure session cookies. It retains automatic Flyway V1–V4 and Hibernate validation, the existing authenticated same-origin WebSocket, in-memory temporary audio/context/sessions, Standard free-tier confirmation guard, and disabled-by-default Premium. No Dockerfile, cloud resource, billed plan, provider call, or manual browser run belongs to Gate 10A. [Owner deployment steps](DEPLOYMENT.md) belong to later Gate 10B.

The owner paused the individual Gate 9A walkthrough after reporting PASS for its first three UI checks. Remaining desktop, tablet/mobile, keyboard/accessibility, two-user, microphone, reconnect, English↔Tamil live Standard, context/glossary, voice-quality, and deployed smoke checks remain pending together in final integrated QA. Gate 10A automated verification cannot close those evidence gaps. A Railway Limited Trial may restrict outbound provider access; an eligible Full Trial or owner-approved paid plan and actual network verification are needed before claiming deployed provider operation.
