# Maven Wrapper — 2026-10-30

Branch: `chore/add-maven-wrapper` (created from `chore/update-dependencies`)

Goal: build the project without a globally installed Maven, with every developer and CI job on the same Maven version.

## Versions

| Item                  | Version       | Notes                                                               |
|-----------------------|---------------|---------------------------------------------------------------------|
| Maven Wrapper         | 3.3.4         | Latest release                                                      |
| Maven (downloaded)    | 3.9.16        | Latest 3.9.x; Maven 4.0.0 is still at release candidate (rc-7)      |
| Distribution type     | `only-script` | Default since wrapper 3.3.0; no `maven-wrapper.jar` to commit       |

## Files

| File                                    | Change   | Purpose                                                                     |
|-----------------------------------------|----------|-----------------------------------------------------------------------------|
| `mvnw`                                  | Added    | Unix/macOS/Git Bash launcher (LF line endings, executable)                  |
| `mvnw.cmd`                              | Added    | Windows launcher (CRLF line endings)                                        |
| `.mvn/wrapper/maven-wrapper.properties` | Added    | Pins the wrapper version, distribution type and Maven download URL          |
| `.gitattributes`                        | Added    | Keeps `mvnw` as LF and `*.cmd` as CRLF on every OS                          |
| `.gitignore`                            | Modified | Removed the rule that ignored `maven-wrapper.properties`                    |
| `README.md`, `CLAUDE.md`                | Modified | Build commands now use `./mvnw`; Maven removed from prerequisites           |
| `pom.xml`                               | Modified | Registers Lombok as an annotation processor; quiets npm notices (see below) |

## Usage

| Shell                 | Command                    |
|-----------------------|----------------------------|
| Bash / zsh / Git Bash | `./mvnw clean install`     |
| PowerShell            | `.\mvnw.cmd clean install` |
| cmd.exe               | `mvnw.cmd clean install`   |

On first run the wrapper downloads Maven 3.9.16 to `~/.m2/wrapper/dists/` and reuses it after that.

## How it was generated

`mvn wrapper:wrapper` could not run because Maven Central was unreachable from the build environment. The scripts were taken from the `maven-wrapper-3.3.4` tag of `apache/maven-wrapper` (`maven-wrapper-distribution/src/resources/only-mvnw` and `only-mvnw.cmd`), with the version placeholder filled in and line endings set the same way the official distribution assembly sets them. The properties file matches what `WrapperMojo` writes for `only-script`.

To regenerate or upgrade later:

```bash
./mvnw wrapper:wrapper -Dmaven=<version>
```

## Build fix: Lombok annotation processing

The first `./mvnw clean install` failed in `HelloController.java` with `cannot find symbol` on `log`.

| Cause | Fix |
|-------|-----|
| Since JDK 23, `javac` no longer runs annotation processors found on the classpath unless they are configured. On JDK 25 Lombok never ran, so `@Slf4j` did not generate `log`. IntelliJ enables annotation processing itself, which hid the problem in the IDE. | Added `maven-compiler-plugin` with Lombok in `annotationProcessorPaths` (same as Spring Initializr). No version is set: compiler plugin 3.12+ takes it from the Spring Boot BOM. |

This failure was already in the code before the wrapper was added. The wrapper build was the first Maven command-line build on JDK 25.

## Build log cleanup: npm notices shown as `[ERROR]`

npm writes `notice` messages (for example `npm notice run frontend@0.0.0 build`) to stderr, and `frontend-maven-plugin` logs all stderr at Maven's `ERROR` level. They did not fail the build.

| Change | Effect |
|--------|--------|
| `npm install` and `npm run build` executions now pass `--loglevel=warn` | npm notices are hidden in the Maven build; npm warnings/errors and all `tsc`/Vite output still show; a failing step still fails the build through its exit code |

Running npm directly in `frontend/` is unchanged.

## Runtime fix: OpenTelemetry log export crash

`opentelemetry-logback-appender-1.0` was moved from 2.33.1-alpha to 2.28.1-alpha so all OpenTelemetry jars match the 1.62.0 SDK that Spring Boot 4.1.2 manages. Details are in `docs/002-dependency-update-2026-10.md`.

## Notes

- `mvnw` was staged with `git add --chmod=+x` because the repo uses `core.filemode=false` on Windows; without it the script would not be executable on Linux/macOS clones or CI.
- No `distributionSha256Sum` is set. To add checksum verification, add `distributionSha256Sum=<sha256 of apache-maven-3.9.16-bin.zip>` to `maven-wrapper.properties`.
- Verified: the `mvnw` script resolves the distribution directory and launches Maven correctly (tested against a pre-seeded distribution, since the download was blocked). Run `./mvnw -v` once on a networked machine to confirm the download.
