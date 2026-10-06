# VoiceLink development plan

Each gate requires its own scope and review before implementation. This document records direction, not authorization to build future features.

| Gate | Scope | Status |
| --- | --- | --- |
| 0 | Verify workspace and local toolchain | Completed |
| 1 | Application foundation, configuration, local database definition, minimal security/page, tests, docs | In progress; acceptance depends on Gate 1 verification |
| 2 | Domain and database design with reviewed Flyway migrations | Planned |
| 3 | Authentication, user preferences, and authorization policy | Planned |
| 4 | Two-participant rooms, conversation state, and transport protocol | Planned |
| 5 | Provider abstractions and selected speech/translation integrations | Planned |
| 6 | Complete user experience, operational hardening, and release checks | Planned |

Later gate boundaries may be revised during planning. No paid AI service or production deployment is part of Gate 1.
