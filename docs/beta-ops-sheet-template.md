# Beta Operations Sheet Template

Use this as the tracked template for the closed-beta manual measurement sheet.
Create the working copy in your spreadsheet tool or `.omx/reports/` during beta operations.

| User ID/email | Invite date | Onboarding complete | Created/edited schedule | AI requests | AI acceptable | AI unacceptable | Google write-back failure | Data loss incident | Sev link | Survey 1-5 | Use again Y/N | Support notes |
|---|---|---|---|---:|---:|---:|---|---|---|---:|---|---|
|  |  |  |  | 0 | 0 | 0 |  |  |  |  |  |  |

## Daily beta checklist

- [ ] Cloud Run service healthy
- [ ] `/actuator/health` healthy through deployed route
- [ ] auth failure logs reviewed
- [ ] AI failure logs reviewed
- [ ] sync/write-back failure logs reviewed
- [ ] support inbox reviewed
- [ ] incidents classified Sev1/Sev2/Sev3
- [ ] AI acceptable/unacceptable counts updated
- [ ] uptime source recorded

## Success thresholds

- invited users >= 30
- beta duration >= 2 weeks
- onboarding completion >= 80%
- create/edit schedule usage >= 70%
- AI usage >= 60%
- acceptable AI result rate >= 80%
- wrong executable AI proposal causing damage = 0
- incorrect Google write-back = 0
- data loss = 0
- Sev1 = 0
- Sev2 <= 1
- uptime >= 99.5%
- survey average >= 4.0 / 5
- willing to use again >= 60%
