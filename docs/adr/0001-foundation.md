# ADR 0001 — M0 platform and source-safety foundation

Date: 2026-09-28. Status: implemented; Docker runtime acceptance pending.

## Decisions

Use the requested modular Groovy/Spring Boot monolith, Angular standalone UI, JDBC/Flyway and PostgreSQL. No ORM, scheduler, source scan, duplicate logic or background fake data is added in M0. Follow M1–M8 in dependency order. The specification and original AGPL license are preserved.

| Component | Exact choice |
|---|---|
| JVM | Java 21; development verification used Temurin 21.0.12.1+1 |
| Spring Boot | 4.1.1 |
| Groovy | 5.0.8 from Boot BOM |
| Spring Security | 7.1.1 from Boot BOM |
| Gradle | 8.14.3 wrapper with distribution SHA-256 and committed wrapper JAR |
| JNA | 5.18.1 |
| PostgreSQL image | 18.6-bookworm, manifest digest pinned in Compose |
| Angular / Material / CDK / CLI / build | 22.0.7 |
| Node | 24.19.0 image, manifest digest pinned |
| TypeScript / RxJS | 6.0.3 / 7.8.2 |
| Frontend runtime | Nginx unprivileged 1.30.5-alpine, digest pinned |
| Tests | JUnit Jupiter via Boot, Testcontainers 2.0.5, Vitest 4.0.18, Playwright 1.63.0 |
| OpenAPI generator | 7.13.0 with isolated TypeScript 5.9.3 tooling |

Gradle dependency locking covers resolvable project configurations. npm lockfiles are committed for the UI and isolated generator. The generator still declares TypeScript 5 as its peer; isolation avoids overriding peer checks while Angular stays on TypeScript 6. No prerelease dependency was selected. Image digests were resolved from registry manifests; image contents have not been executed in Docker here.

## Native boundary

Support Linux/amd64 only. JNA calls libc's `syscall` for `openat2` (437), descriptor-relative `statx`, `fstatvfs`, `read`, `readlinkat`, `fdopendir`, `readdir`, `closedir`, and `close`. Structure layouts and flags live in `NativeLinux`. No private JDK APIs, dynamic Groovy, pathname-only safety substitution, external scan commands, or source write syscalls are present in production code.

Open the configured root with no symlink/magic-link resolution. Pin its directory descriptor. Traverse relative raw-byte components using `RESOLVE_BENEATH`, `RESOLVE_NO_SYMLINKS`, `RESOLVE_NO_MAGICLINKS` and normally `RESOLVE_NO_XDEV`; no absolute/dot/parent/empty/NUL component is accepted. Deep paths resolve component by component. O_PATH plus O_NOFOLLOW permits metadata on the final symlink without following it. File-content handles use read-only O_NONBLOCK, verify regular-file type, and compare descriptor/path fingerprints before acceptance. Directory cursors revalidate the directory entry. There is no file-open route in the API.

The adapter requires statx type/mode/inode/size/mount ID and nanosecond mtime/ctime. Birth time and other optional fields carry null when unsupported. Raw directory bytes remain distinct even when UTF-8 decoding fails. Display representations escape controls; they are never used to open files. Reads are capped at 4 MiB and handles at 64; root closure also closes outstanding child handles. Completion validation requires EOF, exact byte count, stable descriptor metadata and the path still referring to the opened object. M1/M2 must persist and interpret that evidence rather than treating a successful read call as a completed observation/hash.

Require both descriptor `ST_RDONLY` and read-only mountinfo flags. Explicit included submounts must all be read-only. Recheck read-only state before reads/enumeration. Source registry validates configuration identity, rejects application-path overlap, and detects backing-directory bind aliases using mount IDs/backing paths and object identity. Native calls fail closed when unsupported. Root errors remain source-local so database access is available.

JNA loads its library from the application classpath/system libc and application-owned temporary storage. The Compose backend has no source write mount, privilege, Docker socket, host namespace or device access. Read-only application roots and private tmpfs are separate from sources.

## Limits and follow-up

Native fixture tests exercise real syscalls but use a test-only mount guard for generated writable directories. Production writable-root rejection is independently tested. Actual Docker read-only mounts, nested submounts, non-root UID/GID behavior and full-stack image startup remain a required environment gate. Database/browser checks in this workspace used PGlite's PostgreSQL 18.3 engine/protocol as a limited test harness, not certification of native PostgreSQL 18.6 or concurrency. Testcontainers tests target the real pinned PostgreSQL image on a Docker host.

No source mutation, durable jobs, scan APIs, checksum generation, full text extraction, result grouping or exports are delivered by M0. M1 must freeze source snapshots, revalidate on creation/resume, enforce single scheduler ownership and persist all job/control/observation state. API readiness and cached source status must not be confused with a completed scan.

## Primary references checked during implementation

- https://docs.spring.io/spring-boot/system-requirements.html
- https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html
- https://angular.dev/reference/versions
- https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html
- https://man7.org/linux/man-pages/man2/openat2.2.html
- https://man7.org/linux/man-pages/man2/statx.2.html
- https://docs.docker.com/engine/storage/bind-mounts/

The official framework compatibility lines were rechecked; artifact/package and image manifest availability were also checked directly. No change to the product's technology or source-safety requirements was needed.
