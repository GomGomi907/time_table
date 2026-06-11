# Cloud Run custom domain migration — `time-table.cloud`

Status: service migrated to a Cloud Run domain-mapping-supported region; domain ownership verification pending.

## Current target

- Public domain: `https://time-table.cloud`
- Cloud Run service: `timetable`
- Target region for direct domain mapping: `asia-northeast1`
- Current reachable fallback URL: `https://timetable-608682434352.asia-northeast1.run.app`
- Previous service region kept for rollback: `asia-northeast2`
- OAuth redirect URI to register after domain mapping: `https://time-table.cloud/login/oauth2/code/google`

## Completed

The service was redeployed to `asia-northeast1` with:

- `APP_FRONTEND_URL=https://time-table.cloud`
- `NEXT_PUBLIC_SITE_URL=https://time-table.cloud`
- `NEXT_PUBLIC_API_BASE_URL=` so browser API calls stay same-origin through Next rewrites.
- Existing Cloud SQL instance: `gen-lang-client-0555168800:asia-northeast2:timetable-postgres`
- Existing production secrets for DB, Google OAuth, Gemini, and app encryption.

Smoke evidence from the `asia-northeast1` fallback URL:

| Path | Result |
|---|---|
| `/login` | HTTP 200 |
| `/privacy` | HTTP 200 |
| `/terms` | HTTP 200 |
| `/actuator/health` | HTTP 200 |

## Blocker

Cloud Run rejected the direct domain mapping because `time-table.cloud` is not verified for the active Google account/project yet.

Current Gabia DNS evidence:

- `TXT www.time-table.cloud = google-site-verification=amHPb-1KiTBL85r1Ks9ScxlOJE3Dh0thUpTRaebEIDw`
- `TXT time-table.cloud` is not present on Gabia authoritative nameservers.

For the apex URL `https://time-table.cloud`, the Google verification TXT must be added on the root/apex host (`@`, blank, or `time-table.cloud` depending on the Gabia UI), not only on `www`.

```powershell
gcloud beta run domain-mappings create --service=timetable --domain=time-table.cloud --region=asia-northeast1 --platform=managed
```

Observed error:

```text
The provided domain does not appear to be verified for the current account.
To verify it, run: gcloud domains verify time-table.cloud
```

`gcloud domains verify time-table.cloud` opens Search Console:

```text
https://search.google.com/search-console/welcome?authuser=0&new_domain_name=time-table.cloud&pli=1
```

Complete domain verification with the same Google account that owns the Cloud project, or add that account as a verified owner in Search Console.

## Next commands after verification

```powershell
gcloud domains list-user-verified

gcloud beta run domain-mappings create `
  --service=timetable `
  --domain=time-table.cloud `
  --region=asia-northeast1 `
  --platform=managed

gcloud beta run domain-mappings describe `
  --domain=time-table.cloud `
  --region=asia-northeast1 `
  --platform=managed
```

Then add every `resourceRecords` value returned by `domain-mappings describe` at the domain registrar DNS panel. After propagation and Google-managed certificate provisioning, verify:

```powershell
Invoke-WebRequest -UseBasicParsing https://time-table.cloud/login
Invoke-WebRequest -UseBasicParsing https://time-table.cloud/privacy
Invoke-WebRequest -UseBasicParsing https://time-table.cloud/terms
Invoke-WebRequest -UseBasicParsing https://time-table.cloud/actuator/health
```

## Google OAuth configuration

After `https://time-table.cloud` resolves successfully:

1. Google Auth Platform → Branding
   - Homepage: `https://time-table.cloud/`
   - Privacy policy: `https://time-table.cloud/privacy`
   - Terms of service: `https://time-table.cloud/terms`
   - App logo: `frontend/public/brand/time-table-logo-512.png`
2. Google Auth Platform → Branding → Authorized domains
   - Add `time-table.cloud`
3. OAuth client → Authorized redirect URIs
   - Add `https://time-table.cloud/login/oauth2/code/google`

Keep the `asia-northeast1.run.app` URL only as a rollout fallback; the public review URLs should use `time-table.cloud`.
