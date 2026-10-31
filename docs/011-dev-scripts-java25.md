# 011 – Dev scripts pick the JDK 25 installed on the machine

## Summary

The project compiles for Java 25 (`java.version`, `maven.compiler.source/target` in the root `pom.xml`), but `scripts/dev.sh` and `scripts/dev.ps1` did not choose a JDK at all: they ran `./mvnw` / `mvnw.cmd`, which uses `JAVA_HOME` or the first `java` on `PATH`. On a machine where that is an older JDK, the three backends failed one by one with `invalid target release: 25` some seconds after start-up, in three windows at once on Windows. Nothing else in the build guards the version either: no `maven-enforcer-plugin`, no Maven toolchains, no `.mvn/jvm.config`, no `.sdkmanrc` / `.java-version`.

Both scripts now resolve a JDK 25 before starting anything, export it as `JAVA_HOME` (and prepend its `bin` to `PATH`) for the Maven Wrapper, print which one they use, and stop with a clear message when none is found. The Maven build itself is unchanged.

## Resolution order

| Order | Source                                   | `dev.sh`                                                                                                                                           | `dev.ps1` / `dev.cmd`                                                                                                                                                              |
|-------|------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1     | Explicit argument                        | `--java-home <dir>` or `--java-home=<dir>`; must be a JDK 25 or the script exits with code 1                                                       | `-JavaHome <dir>`; same rule (`dev.cmd` passes it through)                                                                                                                         |
| 2     | `JAVA_HOME`                              | Used if it is a JDK 25; otherwise a note is printed and the search continues                                                                       | Same                                                                                                                                                                               |
| 3     | `java` on `PATH`                         | Symlinks resolved (`readlink -f`), parent of `bin` used if it is a JDK 25                                                                          | `Get-Command java.exe`; the Oracle `javapath` shim directory is rejected because it has no `release` file                                                                          |
| 4     | Common install locations                 | `~/.jdks`, `~/.sdkman/candidates/java`, `/usr/lib/jvm`, `/usr/local/lib/jvm`, `/opt`, `/Library/Java/JavaVirtualMachines` (and `~/Library/...`), `~/opt`; `/usr/libexec/java_home -v 25` on macOS | Every subdirectory of `%USERPROFILE%\.jdks` (IntelliJ downloads), `Program Files\Amazon Corretto`, `\Java`, `\Eclipse Adoptium`, `\Microsoft`, `\Zulu`, `\BellSoft`, `%LOCALAPPDATA%\Programs\Eclipse Adoptium`, `~\scoop\apps\*\current` |
| —     | Nothing found                            | Lists what was checked, suggests `--java-home` / `JAVA_HOME`, exit code 1, no server started                                                        | Same, with `-JavaHome`                                                                                                                                                             |

"Is a JDK 25" means: the directory has `bin/javac` (so JREs are rejected) and a `release` file whose `JAVA_VERSION` starts with `25`. Exactly 25, not "25 or later": that is what `java.version` says and what IntelliJ is configured with (`.idea/misc.xml`: `corretto-25`), so the scripts and the IDE agree. Running `./mvnw` by hand with a newer JDK still works, since javac targets release 25.

In step 4 the candidates are sorted descending by name, so `jdk-25.0.2` is preferred over `jdk-25.0.1` when both are installed.

## Changed files

| File                                 | Change                                                                                                                                                             |
|--------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `scripts/dev.sh`                     | `--java-home` option; `jdk_version`, `is_required_jdk`, `path_java_home`, `find_installed_jdks`, `select_jdk`; exports `JAVA_HOME`/`PATH`; prints `[dev] using JDK 25: <dir> (<java -version>)` |
| `scripts/dev.ps1`                    | `-JavaHome` parameter; `Get-JdkVersion`, `Test-RequiredJdk`, `Get-PathJavaHome`, `Find-InstalledJdks`, `Select-Jdk`; sets `$env:JAVA_HOME`/`$env:Path` before the server windows are spawned, so they inherit it; prints `[dev] using JDK 25: <dir> (<IMPLEMENTOR> <JAVA_VERSION>)` from the `release` file |
| `README.md`, `CLAUDE.md`             | Mention the JDK selection and the new option                                                                                                                       |

`dev.cmd` is unchanged; it already forwards all arguments to `dev.ps1`.

Why `dev.ps1` reads the version from the `release` file instead of running `java -version`: `java -version` prints to stderr, and under `$ErrorActionPreference = 'Stop'` Windows PowerShell 5.1 turns stderr output captured with `2>&1` into a terminating `NativeCommandError`. The `release` file is present in every JDK 9+ and has the same information.

## Usage

| Situation                                                      | What to do                                                                                   |
|----------------------------------------------------------------|----------------------------------------------------------------------------------------------|
| JDK 25 is `JAVA_HOME`, on `PATH`, or installed in a usual place | Nothing; `scripts/dev.sh` / `scripts\dev.cmd` work as before and print the JDK they picked   |
| JDK 25 is somewhere unusual                                    | `scripts/dev.sh --java-home /path/to/jdk-25`, `scripts\dev.cmd -JavaHome C:\path\to\jdk-25`  |
| Several JDK 25 builds installed                                | Set `JAVA_HOME` or pass the option to choose; otherwise the highest name in the first matching location wins |

## Verification

The selection logic of `dev.sh` was exercised on Linux with a JDK 25 in `~/opt/jdk-25`, a JRE 11 as the default `java`, and the script cut off just before the servers start:

| Scenario                                              | Result                                                                                       |
|-------------------------------------------------------|----------------------------------------------------------------------------------------------|
| Nothing set, JRE 11 on `PATH`                         | Auto-detected `~/opt/jdk-25`, exit 0                                                         |
| `JAVA_HOME` = JDK 25                                  | Used directly                                                                                |
| `JAVA_HOME` = JRE 11                                  | `JAVA_HOME=... is not a JDK (no bin/javac or release file), need JDK 25; looking elsewhere`, then auto-detected |
| `--java-home <jdk-25>`                                | Used directly                                                                                |
| `--java-home /usr`                                    | `--java-home /usr is not a JDK (...), need JDK 25`, exit 1                                   |
| `HOME` pointed at an empty directory, no JDK 25 at all | Message listing the checked locations, exit 1                                                |
| JDK 25 first on `PATH`, `HOME` empty                   | Picked from `PATH`                                                                           |
| `--java-home` without a value                         | `--java-home needs a directory`, exit 2                                                      |

`bash -n scripts/dev.sh` passes and `--help` shows the new option. With the JDK the script selects, `./mvnw compile -pl backends/stocks -am` succeeded (`Compiling 7 source files with javac [debug parameters release 25]`, `BUILD SUCCESS`). A full `./mvnw clean install` could not be completed in that environment: it stopped in the `fs-web-frontend` module while the `frontend-maven-plugin` unpacked Node, unrelated to this change.

Not yet run, to do on Windows before merging:

| Check                         | Expected                                                                                          |
|-------------------------------|---------------------------------------------------------------------------------------------------|
| `mvnw.cmd clean install`      | `BUILD SUCCESS` (no Java or POM change, so same as on `main`)                                     |
| `scripts\dev.cmd`             | Launcher prints `[dev] using JDK 25: <dir> (...)`, then the four server windows open and start    |
| `scripts\dev.cmd -JavaHome C:\nonexistent` | `[dev] -JavaHome C:\nonexistent is not a JDK (...), need JDK 25`, no windows open      |

`dev.ps1` could not be executed in the Linux environment this was prepared in (no PowerShell there).
