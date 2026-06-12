# Cloud Run custom domain migration — `time-table.cloud`

Status: live on Cloud Run direct domain mapping. Google-managed certificate is provisioned, apex A records are visible from public DoH resolvers, and Google OAuth no longer returns `redirect_uri_mismatch`.

## Current target

- Public domain: `https://time-table.cloud`
- Cloud Run service: `timetable`
- Target region for direct domain mapping: `asia-northeast1`
- Current reachable fallback URL: `https://timetable-608682434352.asia-northeast1.run.app`
- Previous service region kept for rollback: `asia-northeast2`
- OAuth redirect URI: `https://time-table.cloud/login/oauth2/code/google`

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

Cloud Run direct domain mapping is ready:

```powershell
gcloud beta run domain-mappings describe `
  --domain=time-table.cloud `
  --region=asia-northeast1 `
  --project=gen-lang-client-0555168800 `
  --platform=managed
```

Observed status:

```text
Ready: True
CertificateProvisioned: True
DomainRoutable: True
```

## Gabia DNS records

Apex/root A records are configured:

| Host | Type | Value |
|---|---|---|
| `@` | `A` | `216.239.32.21` |
| `@` | `A` | `216.239.34.21` |
| `@` | `A` | `216.239.36.21` |
| `@` | `A` | `216.239.38.21` |

Cloud Run also supplied AAAA records, but Gabia did not expose an AAAA option during setup. IPv4 A records are sufficient for the current HTTPS deployment.

Public DoH evidence:

```text
dns.google A time-table.cloud -> 216.239.32.21, 216.239.34.21, 216.239.36.21, 216.239.38.21
cloudflare A time-table.cloud -> 216.239.32.21, 216.239.34.21, 216.239.36.21, 216.239.38.21
```

Some local/system resolvers can lag behind public DoH and still return no A record during propagation. Use `https://dns.google/resolve?name=time-table.cloud&type=A` as the cross-check source when diagnosing resolver cache delay.

## Google OAuth configuration

Google OAuth client `608682434352-84cplp66gcmc5eq29fpdb1mlidrv5rp9.apps.googleusercontent.com` must keep:

- Authorized redirect URI: `https://time-table.cloud/login/oauth2/code/google`
- Recommended authorized JavaScript origin: `https://time-table.cloud`

Verification evidence after adding the redirect URI:

```text
https://time-table.cloud/oauth2/authorization/google
-> Google Sign in page
-> no redirect_uri_mismatch / Error 400 markers
```

Keep the `asia-northeast1.run.app` URL only as a rollout fallback; public review URLs should use `time-table.cloud`.
