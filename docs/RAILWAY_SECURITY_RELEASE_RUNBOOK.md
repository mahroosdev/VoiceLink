# Railway security and release checklist

This operational checklist does not authorize a deployment, credential change, database change, or provider request. Verify current project/environment/service identities in Railway first. Never copy secret values into a report, terminal log, screenshot, or repository.

## Before credential rotation

1. Confirm the intended Railway project, environment, application service, GitHub repository/branch, current deployed commit, build status, health check, and pending deployment state.
2. Confirm application database variables reference the intended PostgreSQL service and private connection. Record service and volume identities without exposing credentials. Identify any other database consumers.
3. Create a backup of the correct database and verify that it can be restored to a separate scratch target. A successful snapshot alone is not a restore test. Preserve the production volume.
4. Inventory provider/email credentials by variable name and destination only. Review provider usage and available audit events. Prepare replacements in the provider's console and coordinate which consumers must receive them. Do not revoke old credentials until replacement service health is confirmed and all consumers have been updated.
5. For database password changes, use Railway's currently supported database credential regeneration procedure. Verify how linked references update, coordinate all dependent application restarts, then confirm database connectivity and Flyway/schema validation. Do not edit one side of a credential pair independently.

## Controlled deployment

1. Confirm the exact reviewed commit and a clean intended worktree. Review migration compatibility and backup/restore readiness.
2. Keep Standard provider execution disabled during infrastructure-only deployment checks. Keep `VOICELINK_PREMIUM_ENABLED=false` unless a separate paid-provider release is explicitly approved.
3. Push through the approved normal Git workflow and allow the connected source to create one deployment. Do not force-push or trigger a duplicate deployment.
4. Verify deployed commit, build result, health endpoint, database connection, Flyway state, page/assets, and safe logs. Do not infer complete user workflow or provider quality from a health check.
5. If deployment fails, stop further promotion. Inspect rollback behavior and variable snapshots before reverting. Application rollback does not undo a Flyway migration. Use a verified restore plan only with separate authorization.

## Provider review

Before any live provider call, independently confirm the exact account/project tier, current quota, privacy terms, model access, and required server-side confirmation flags. Obtain explicit authorization and use synthetic, non-sensitive content. Keep all provider keys server-side. Never activate Premium or use a paid fallback implicitly.

Check Railway's and each provider's current official security guidance at execution time; procedures, UI labels, plans, and credential behavior may change.
