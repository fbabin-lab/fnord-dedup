# Implemented API — M0

`openapi.yaml` is the contract for implemented routes. The full v1 endpoint inventory in `SPECIFICATION.md` is a future delivery requirement, not a list of currently available routes.

All application URLs are under `/api/v1`. The frontend uses relative same-origin URLs through Nginx. No CORS allowance is configured. The generated Angular schema is `frontend/src/app/api-schema.ts`; regenerate it using `npm run api:generate` from `frontend`.

| Method | Route | Behavior |
|---|---|---|
| GET | `/session` | Anonymous or authenticated session state; issues separate readable XSRF cookie. |
| POST | `/session/login` | Form-encoded username/password plus CSRF header/cookie; 204 on success, 401 on invalid credentials, 403 for CSRF failure, 429 for throttling. |
| POST | `/session/logout` | Requires CSRF; destroys the session and cookies, returns 204. |
| GET | `/sources` | Authenticated configured source list and configuration revision; cached startup validation, no source reads. |
| GET | `/system/info` | Authenticated software/capability information; `scanAvailable` is false. |
| GET | `/system/health` | Public minimal application/database readiness; 200 UP or 503 DOWN. |

`GET /actuator/health` and probe subpaths are internal Spring health endpoints; Nginx does not proxy `/actuator`. No database detail is returned. Runtime `/api` source-download, arbitrary-file, mutation, execution and scan routes do not exist.

## Browser session sequence

1. GET `/session` to load state and the `XSRF-TOKEN` cookie.
2. POST form data to `/session/login` with `X-XSRF-TOKEN` equal to that cookie.
3. GET `/session` again to refresh the token after authentication; Spring clears the pre-login token.
4. Send the same header on state-changing requests. Angular's normal XSRF support handles relative URLs.
5. POST `/session/logout`, then GET `/session` to obtain a new anonymous token.

The session cookie is HttpOnly and SameSite=Strict. The XSRF cookie is readable and SameSite=Strict. Set `FNORD_SECURE_COOKIES=true` for HTTPS; HTTP localhost uses false. Login is limited globally to five processed attempts per minute for this single-operator application. The limit is process-local and resets on restart. This is not a distributed brute-force or DDoS protection service.

Authentication/CSRF errors use the problem schema with a generated correlation ID; caller-supplied correlation values are not trusted. Source validation errors are records in `/sources`, so an unavailable root does not prevent login or database browsing. Cached status does not certify a later mount state; M1 will revalidate before scan/resume.

## Contract tooling

Angular requires TypeScript 6, while the pinned OpenAPI generator declares a TypeScript 5 peer dependency. Generator dependencies are isolated under `tools/api-codegen` with their own lockfile. Both installations use `npm ci`; no peer checks are disabled. `npm run api:check` detects a stale committed DTO schema.
