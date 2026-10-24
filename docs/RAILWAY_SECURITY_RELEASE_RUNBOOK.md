# Railway security and staged Standard release — non-executing runbook

This is a preparation checklist, not authorization to change Railway, GitHub, PostgreSQL, or credentials. The current local candidate and prior production state have not been rechecked through Railway. Use the actual service names and current status in the authenticated Railway project; never paste secret values into a report, terminal transcript, screenshot, or repository. Keep `VOICELINK_PREMIUM_ENABLED=false` and Standard provider calls disabled until their separate owner-approved validation.

Railway's [October 21 disclosure](https://blog.railway.com/p/security-disclosure-wednesday-october-21st) and [preliminary report](https://blog.railway.com/p/preliminary-report-october-security-incident) recommend replacing sensitive variables stored before the incident. Railway reports that builds for all plan tiers resumed, but that platform-wide statement does **not** establish this project's GitHub reconnection, secret rotation, backup readiness, or deployed commit. The Codex Railway connector still required reauthentication at the 2026-10-24 local check.

## Preconditions and security coordination

1. Reauthenticate to Railway, identify the intended project/environment, and confirm the application service is connected to the approved VoiceLink GitHub repository on `main`. Railway [reset GitHub connection tokens](https://blog.railway.com/p/security-disclosure-recommendation-redeploy-buckets-and-reconnect-github-after-the-credential-reset): check the account connection, GitHub App repository access, service **Settings → Source**, branch and autodeploy trigger before pushing. Record the current deployed commit, pending staged changes, deployment status, health-check configuration, and exact PostgreSQL service/volume identities. Stop if builds are paused, the source is wrong, another deploy is pending, or project/volume identity is uncertain.
2. Confirm application `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` are **references to the intended PostgreSQL service** (`PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`), not copied literals or a stale database. Check the JDBC prefix and private TLS mode without printing resolved credentials. Identify all external database consumers and check whether a public TCP proxy exists; if it does, determine whether it is still required before any separately authorized removal. Keep the database volume attached; do not delete, recreate, or reset it as a rotation shortcut.
3. Before any database credential regeneration or source deployment, obtain a verified backup of the correct database. Use Railway's Backups tab for a manual volume snapshot where available, plus an owner-controlled logical dump if required by the recovery plan. Verify the backup identifier/time and perform a restore drill to a **separate scratch target**; record restore success and data/Flyway checks without exposing data. A snapshot alone is not a proven restore. Do not use production as the drill target. Confirm the selected plan permits the backup/restore approach and any storage cost before execution.
4. Inventory Groq, Standard Gemini, and optional password-reset email keys by **name and destination only**. Review provider usage and Railway/GitHub audit logs for anomalous access. Prepare replacement keys in their respective provider consoles; stage them as Railway secrets, keeping Standard calls disabled. After app health and a separately authorized non-sensitive provider check succeed, revoke old keys in the provider consoles and confirm no old consumer remains. Do not rotate or reveal keys automatically. Premium key/project remain unused. Also inventory any Railway CLI/CI or registry tokens; Railway's incident response [revoked some platform and GitHub tokens](https://blog.railway.com/p/security-disclosure-recommendation-recover-failing-deploys), so their owners must replace them if used.
5. For PostgreSQL password rotation, use Railway's database **Config → Connection → Regenerate** procedure after the verified backup. Railway's [incident-specific guide](https://blog.railway.com/p/security-disclosure-recommendation-rotate-postgresql-database-credentials) says this updates the database password and linked variables together and restarts PostgreSQL; manually editing `PGPASSWORD` or running `ALTER USER` alone can desynchronize them. Coordinate **every dependent application's** redeployment so references resolve the new password; expect old connections to break until that redeployment. Update any authorized external consumers separately. Confirm the new app connects, database Data view works, Flyway/schema validation succeeds, and old-credential authentication failures cease before declaring the rotation complete.

## Release order and verification

6. First release **only J4/J4.1** from the already tested local `main` checkpoint, after the owner approves the exact commit and security readiness. Push normally; let the connected Railway service create **one** deployment. Do not merge J5 or trigger a second deploy. Confirm the active deployed commit, exact homepage mock text, CSS/JavaScript assets, `/actuator/health`, safe logs, database connectivity, and expected Flyway version. Keep Standard disabled during this initial infrastructure check.
7. The integrated J5 candidate on `release/standard-final` is a **separate later promotion**. Require owner authorization after the two-browser worksheet, full regression evidence, V8 backup/restore readiness, and first-release stability. Review the merge diff and deployed commit; deploy normally with no force push. Check registration/login, two-person room membership and WebSocket direction, V8 migration on existing rows, homepage assets, desktop/mobile behavior, and safe logs. Do not infer live provider quality from health status or offline tests.
8. If app deployment fails, stop further promotion. Use Railway's previous known-good **app deployment rollback** only after checking its image/variable snapshot; Railway notes rollback restores **custom variables as well as the image**, so ensure rotated credentials are not silently replaced by old values. A code rollback does not undo a Flyway migration. Handle database recovery only through the verified backup/restore plan and explicit owner authorization; never delete or reset the volume to recover. Recheck app health, database connectivity, Flyway state, and authentication after any recovery.

## Prepared Git commands — do not execute until each release is authorized

These PowerShell blocks are execution templates, not actions performed by this runbook. Run each only after confirming the actual GitHub and Railway state, a clean checkout, completed security prerequisites, and the owner's explicit approval for that specific release. If any comparison fails, stop. Railway's connected `main` source should then auto-deploy once; do not request a second deployment.

**Release A — J4/J4.1 only.** The expected starting remote commit was `8d9f12ac8f503aab37087d4c78a9304d1e08ae39` when this plan was written. The target is local `main` at `2fae71cad7b5d523769911caa50d5eb8f8ed2cd0`.

```powershell
if (git status --porcelain) { throw 'Worktree is not clean.' }
$remoteMain = ((git ls-remote origin refs/heads/main) -split '\s+')[0]
if ($remoteMain -ne '8d9f12ac8f503aab37087d4c78a9304d1e08ae39') { throw 'Remote main changed; re-plan.' }
git switch main
if ((git rev-parse HEAD) -ne '2fae71cad7b5d523769911caa50d5eb8f8ed2cd0') { throw 'Local main changed; stop.' }
git merge-base --is-ancestor $remoteMain HEAD
if ($LASTEXITCODE -ne 0) { throw 'Release A is not a fast-forward.' }
git push origin main:main
if ($LASTEXITCODE -ne 0) { throw 'Push failed; do not deploy manually.' }
git ls-remote origin refs/heads/main
```

After Railway settles, confirm the deployed commit is exactly Release A, `/actuator/health` is UP, the current homepage mock translation and CSS/JavaScript assets are present, PostgreSQL remains connected, Flyway is V7, and safe logs show no new error. Do not begin Release B until the owner accepts this result.

**Release B — integrated J5 Standard.** Replace the placeholder only with the owner-approved, fully tested final `release/standard-final` hash. This stage includes Flyway V8 and must have its own verified backup/restore and browser QA evidence. The first release must already be healthy.

```powershell
$approvedJ5 = '<owner-approved full release/standard-final SHA>'
if (git status --porcelain) { throw 'Worktree is not clean.' }
git switch release/standard-final
if ((git rev-parse HEAD) -ne $approvedJ5) { throw 'J5 candidate changed; stop.' }
git fetch origin main
if ($LASTEXITCODE -ne 0) { throw 'Fetch failed; stop.' }
if ((git rev-parse origin/main) -ne '2fae71cad7b5d523769911caa50d5eb8f8ed2cd0') { throw 'Release A remote state changed; re-plan.' }
git merge-base --is-ancestor origin/main release/standard-final
if ($LASTEXITCODE -ne 0) { throw 'J5 is not a fast-forward; stop.' }
git switch main
git merge --ff-only origin/main
if ($LASTEXITCODE -ne 0) { throw 'Local main cannot advance safely; stop.' }
git merge --ff-only release/standard-final
if ($LASTEXITCODE -ne 0) { throw 'J5 promotion cannot fast-forward; stop.' }
git push origin main:main
if ($LASTEXITCODE -ne 0) { throw 'Push failed; do not deploy manually.' }
git ls-remote origin refs/heads/main
```

Confirm Railway's active commit equals `$approvedJ5`, health is UP, database/Flyway V8 and legacy participant rows are valid, login and two-person room access work, assets and mobile layout load, and no private data appears in logs. App rollback may restore an older code image and variable snapshot, but it **cannot reverse V8**; use the verified database recovery procedure only with separate authorization.

Official references: [database password Regenerate](https://docs.railway.com/databases/database-view), [Postgres backup and restore](https://docs.railway.com/guides/postgres-backups-restores), [reference variables](https://docs.railway.com/variables), and [deployment rollback behavior](https://docs.railway.com/deployments/deployment-actions). Verify these procedures against the live Railway UI at execution time.
