# Cloud Run custom domain migration — `time-table.cloud`

Status: Cloud Run domain mapping created; Gabia A/AAAA DNS records are pending before Google-managed certificate provisioning can complete.

## Current target

- Public domain: `https://time-table.cloud`
- Cloud Run service: `timetable`
- Target region for direct domain mapping: `asia-northeast1`
- Current reachable fallback URL: `https://timetable-608682434352.asia-northeast1.run.app`
- Previous service region kept for rollback: `asia-northeast2`
- OAuth redirect URI to register after domain DNS/certificate is live: `https://time-table.cloud/login/oauth2/code/google`

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

Google domain ownership is verified for the Cloud account/project:

```powershell
gcloud domains list-user-verified --project=gen-lang-client-0555168800
# ID
# time-table.cloud
```

Cloud Run direct domain mapping was created:

```powershell
gcloud beta run domain-mappings create `
  --service=timetable `
  --domain=time-table.cloud `
  --region=asia-northeast1 `
  --project=gen-lang-client-0555168800 `
  --platform=managed
```

Current mapping status:

```text
Ready: Unknown
Reason: CertificatePending
Message: Waiting for certificate provisioning. You must configure your DNS records for certificate issuance to begin.
DomainRoutable: True
```

## Required Gabia DNS records

Add these records at the apex/root host in Gabia (`@`, blank, or `time-table.cloud` depending on the Gabia UI):

| Host | Type | Value |
|---|---|---|
| `@` | `A` | `216.239.32.21` |
| `@` | `A` | `216.239.34.21` |
| `@` | `A` | `216.239.36.21` |
| `@` | `A` | `216.239.38.21` |
| `@` | `AAAA` | `2001:4860:4802:32::15` |
| `@` | `AAAA` | `2001:4860:4802:34::15` |
| `@` | `AAAA` | `2001:4860:4802:36::15` |
| `@` | `AAAA` | `2001:4860:4802:38::15` |

Current DNS evidence after mapping creation still shows no apex A/AAAA records:

```text
Resolve-DnsName time-table.cloud A    -> no A record yet
Resolve-DnsName time-table.cloud AAAA -> no AAAA record yet
```

## Verify after Gabia DNS is updated

After propagation and Google-managed certificate provisioning, verify:

```powershell
gcloud beta run domain-mappings describe `
  --domain=time-table.cloud `
  --region=asia-northeast1 `
  --project=gen-lang-client-0555168800 `
  --platform=managed

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

Keep the `asia-northeast1.run.app` URL only as a rollout fallback; the public review URLs should use `time-table.cloud` once DNS and certificate provisioning complete.
