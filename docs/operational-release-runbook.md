# Operational Release Runbook — Closed Beta

Status: beta-readiness draft  
Target: Google Cloud Run + PostgreSQL/Cloud SQL + Spring Boot backend + Next.js frontend  
Scope: 30+ invited users, 2+ weeks, no public signup or billing

## 1. Release rule

Do not invite beta users until every P0 gate in
`.omx/plans/test-spec-operational-release-readiness-20260530T152253Z.md` has fresh evidence.

Hard no-go conditions:

- beta runtime uses H2 or omits `APP_RELEASE_MODE=beta`
- H2 console is enabled, or beta sessions keep the local fallback timeout instead of an explicit `APP_SESSION_TIMEOUT`
- mock login or mock Google sync is enabled
- OAuth/Gemini/DB secrets are in source, screenshots, logs, or build context
- Google OAuth/Calendar/Tasks live smoke tests have not passed
- Gemini normal, ambiguous, malformed-output, and provider-failure paths have not passed
- PostgreSQL backup/restore has not been rehearsed
- rollback has not been rehearsed
- beta metrics cannot be measured
- any data-loss or incorrect Google write-back risk remains unmitigated

## 2. Deployment topology

Default beta topology: one Cloud Run service from the root `Dockerfile`.

- Next.js listens on Cloud Run `PORT`.
- Spring Boot listens internally on `BACKEND_PORT` through `SERVER_PORT`.
- `/api`, `/oauth2`, `/login/oauth2`, and `/actuator` are routed by Next rewrites to Spring Boot.

Fallback to split frontend/backend Cloud Run services if:

1. backend liveness cannot be proved through the combined service;
2. frontend stays healthy while backend is unavailable;
3. one service identity creates unacceptable secret/IAM blast radius;
4. Cloud SQL/recovery is safer with backend-only identity;
5. beta incident response needs independent backend rollback.

## 3. Required beta environment values

Do not paste values into reports. Record only secret names, versions, and deployment revision IDs.

| Area | Variable | Source |
|---|---|---|
| Runtime mode | `APP_RELEASE_MODE=beta` | explicit beta fail-closed guard |
| Runtime guard | `APP_REQUIRE_SAFE_RUNTIME=true` | optional non-Cloud-Run fail-closed guard |
| Session | `APP_SESSION_TIMEOUT=4h` or shorter | public/shared-device risk reduction |
| Frontend origin | `APP_FRONTEND_URL` | Cloud Run service URL/custom domain |
| Backend DB | `APP_DB_URL` | Cloud SQL PostgreSQL JDBC URL |
| Backend DB | `APP_DB_USERNAME` | Secret Manager pinned version |
| Backend DB | `APP_DB_PASSWORD` | Secret Manager pinned version |
| Google OAuth | `GOOGLE_CLIENT_ID` | Secret Manager pinned version or non-secret env |
| Google OAuth | `GOOGLE_CLIENT_SECRET` | Secret Manager pinned version |
| Gemini | `APP_AI_ENABLED=true` | explicit beta env |
| Gemini | `APP_GEMINI_API_KEY` | Secret Manager pinned version |
| Safety | `APP_AUTH_MOCK_LOGIN_ENABLED=false` | explicit beta env |
| Safety | `APP_SYNC_GOOGLE_MOCK_ENABLED=false` | explicit beta env |
| Safety | `APP_H2_CONSOLE_ENABLED=false` | explicit beta env |

## 4. Pre-deploy hygiene checklist

```powershell
git status --short --branch
git ls-files | Select-String -Pattern 'client_secret|credentials|\\.env$|gemini|api[_-]?key|secret'
Get-Content .dockerignore | Select-String -Pattern 'client_secret_\\*\\.json|\\.env|\\.local'
```

Pass conditions:

- no tracked credential files;
- `.dockerignore` excludes `.env*`, `.local`, and `client_secret_*.json`;
- no secret values are copied into docs or reports.

## 5. Build and deploy evidence

Accept one of:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-release.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-release.ps1 -Full -RequireDocker
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-deployed-release.ps1 -BaseUrl https://timetable-608682434352.asia-northeast2.run.app/
```

`-Full -RequireDocker` is intentionally fail-closed: if Docker/Testcontainers cannot run, the report must say BLOCKED and beta promotion waits for Cloud Build/CI evidence from a clean checkout.

After deploy, record:

- Cloud Run service name
- region
- revision
- image digest
- deployed URL
- deploy time
- operator

Smoke checks:

```powershell
curl -fsS https://<service-url>/login
curl -fsS https://<service-url>/actuator/health
```

The backend health path must fail or show controlled unavailable behavior if Spring Boot is unavailable. A healthy frontend alone is not enough.

Release CI must treat these as required gates, not optional local checks:

- `scripts/verify-release.ps1 -Full -RequireDocker` report from a clean checkout;
- root Docker image build from clean checkout;
- container smoke for `/login` and `/actuator/health`;
- backend-death supervision check for the combined container;
- `PostgresFlywayMigrationTest` against real PostgreSQL/Testcontainers without being skipped.

If Docker is unavailable locally, the local report may mark these blocked, but Cloud Build/CI must produce passing evidence before beta access opens.

## 6. PostgreSQL / Cloud SQL gate

Choose one connection mode before deploy:

- Cloud SQL Unix socket path, or
- Cloud SQL connector / managed connector path.

Evidence required:

1. empty PostgreSQL database boots and runs Flyway successfully;
2. app restart against migrated PostgreSQL has no checksum/validation error;
3. backup/snapshot/export created;
4. backup restored to a separate database;
5. restored DB can serve a smoke app flow or integrity query;
6. H2 is absent from beta runtime logs/env.

Local developer H2 checksum mismatch does not block release if the fresh PostgreSQL gate passes.

## 7. Live Google smoke test

Use a safe beta test account and safe test calendar/task objects.

1. Register deployed callback:
   `https://<service-url>/login/oauth2/code/google`
2. Login through Google OAuth.
3. Read known calendar event.
4. Create/update one safe test event.
5. Read known task.
6. Create/update one safe test task.
7. Revoke/deny token and confirm reconnect UX plus identifiable logs.

Incorrect external write-back is a Sev1-class no-go.

## 8. Live Gemini smoke test

Required requests:

1. clear Korean scheduling request -> valid structured approval-gated proposal;
2. ambiguous request -> clarification or non-executable safe response;
3. provider failure/timeout -> retry-oriented UX, no crash, no mutation;
4. malformed provider output in non-prod/fake path -> rejected safely.
5. multi-scenario live smoke via `scripts/llm-live-probe.ps1 -ScenarioSet` -> report under `.omx/reports/` with p95 latency, command count, privacy exposure score, estimated character size, and PASS safety verdicts.

No prompt, reasoning trace, validation internals, or provider metadata may appear in the UI.

## 9. Operations routine during beta

Daily:

- check Cloud Run health/uptime;
- review auth failures;
- review AI failures;
- review sync/write-back failures;
- update beta operations sheet;
- classify AI results acceptable/unacceptable;
- log Sev1/Sev2/Sev3 incidents.

Pause beta immediately for:

- data loss;
- incorrect Google write-back;
- widespread login outage;
- security incident;
- inability to measure uptime/incidents.

## 10. Recovery playbooks

### Rollback

1. Identify last healthy Cloud Run revision.
2. Route traffic back to that revision.
3. Verify `/login` and `/actuator/health`.
4. Record incident and user impact.

### DB restore

1. Stop beta write traffic if data integrity is suspect.
2. Restore latest known-good backup to a separate DB.
3. Verify schema and sampled user data.
4. Decide whether to point beta app to restored DB or repair manually.
5. Record exact timeline and affected users.

### Bad schedule change

1. Identify user, schedule block/event/task IDs, and source action.
2. Check whether Google write-back occurred.
3. Restore local schedule state from DB/history/manual support record.
4. If Google was changed, repair with testable user confirmation.

## 11. Final go/no-go report

Write `.omx/reports/release-readiness-<timestamp>.md` with:

- verdict: RELEASE / CONDITIONAL INTERNAL REHEARSAL / NO-GO
- evidence table for every P0 test ID
- open risks and owners
- Cloud Run revision/build evidence
- PostgreSQL migration/backup/restore evidence
- Google/Gemini smoke evidence
- desktop/mobile QA evidence
- ops sheet/runbook links
- explicit beta invitation decision
