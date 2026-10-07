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
| 5B | Standard/free-tier STT, translation, and TTS foundation | PASS WITH LIVE PROVIDER VALIDATION PENDING; 49 fast tests, clean package, and 3 PostgreSQL integration tests passed |
| 6 | Complete user experience, operational hardening, and release checks | NOT STARTED |

Later gate boundaries may be revised during planning. No paid AI service or production deployment is part of Gate 1.

Evidence is recorded in [the project log](PROJECT_LOG.md), [testing record](TESTING.md), and [provider evaluation](AI_PROVIDER_EVALUATION.md). Gate 3A was accepted before Gate 3B implementation, Gate 4A before Gate 4B, and Gate 5A before Gate 5B. Gate 5B is limited to Standard; Premium, full context-aware translation, and persistent glossary work require later approval. The Gate 4B owner integrated browser check and Gate 5B live-provider/Tamil quality check remain pending. The next step is owner validation and later-gate planning.
