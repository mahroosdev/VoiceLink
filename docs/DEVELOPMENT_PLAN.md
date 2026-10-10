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

Later gate boundaries may be revised during planning. No paid AI service or production deployment is part of Gate 1.

Evidence is recorded in [the project log](PROJECT_LOG.md), [testing record](TESTING.md), and [provider evaluation](AI_PROVIDER_EVALUATION.md). Gate 3A was accepted before Gate 3B implementation, Gate 4A before Gate 4B, Gate 5A before Gate 5B, and Gate 6A before Gate 6B. Gate 6B and Gate 6C received separate implementation authorization and passed automated verification. Premium work remains outside these gates. The Gate 4B owner integrated browser check and live-provider/Tamil quality check remain pending.

## Gate 6A approved boundaries

Conversation context stays in memory and is never stored as transcript/history in PostgreSQL. A translation may reference at most the newest three eligible prior turns, each no older than five minutes and no longer than 400 Unicode code points, with a combined maximum of 1,200 Unicode code points. Context is reference-only; Gemini translates only the current utterance. Context is lost on application restart and cleared when the room closes.

The technical glossary is specific to a room and persisted in PostgreSQL through the approved V3 `room_glossary_entries` migration. A room may have at most 12 entries. Entries survive browser refresh, reconnect, and application restart while the room is open; explicit room closure deletes them. Only authenticated room members may access them. Source terms are 1–48 Unicode code points and preferred terms are 1–64; a blank preferred term in the UI explicitly means preserve the source term. Normalize terms with NFC, trim and collapse whitespace, and use locale-independent lowercase lookup for uniqueness. Match Latin terms case-insensitively as whole phrases, with longer terms taking precedence when matches overlap. Edits use optimistic `row_version` handling.

Context and glossary data may be sent to Gemini Free Tier. Standard/free development remains limited to non-sensitive, non-confidential conversations. Do not log context, glossary contents, raw Gemini prompts, provider bodies, transcripts, translations, or audio. Gate 6B implemented these approved boundaries; actual live translation quality remains unverified.
