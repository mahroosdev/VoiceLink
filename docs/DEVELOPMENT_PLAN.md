# VoiceLink development plan

Each gate requires its own scope and review before implementation. This document records direction, not authorization to build future features.

| Gate | Scope | Status |
| --- | --- | --- |
| 0 | Verify workspace and local toolchain | PASS |
| 1 | Application foundation, configuration, local database definition, minimal security/page, tests, docs | PASS; includes owner-performed PostgreSQL/runtime verification |
| 1.5 | Record evidence and publish a public GitHub foundation checkpoint | PASS; public `main` push and remote verification completed |
| 2A | Account, preferences, session security, and migration plan | PASS / APPROVED by owner |
| 2B | Implement accounts, preferences, registration, session login/logout, and verification | PASS; fast tests, clean package, and PostgreSQL 17.11 Testcontainers integration verified by Codex |
| 3 | Next design gate; scope requires separate acceptance | NOT STARTED |
| 4 | Two-participant rooms, conversation state, and transport protocol | NOT STARTED |
| 5 | Provider abstractions and selected speech/translation integrations | NOT STARTED |
| 6 | Complete user experience, operational hardening, and release checks | NOT STARTED |

Later gate boundaries may be revised during planning. No paid AI service or production deployment is part of Gate 1.

Evidence is recorded in [the project log](PROJECT_LOG.md) and [testing record](TESTING.md). Gate 2A was accepted and Gate 2B passed on the current automated evidence. Gate 3 needs its own accepted plan before implementation.
