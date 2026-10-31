# 012 – JDK 25 through a Maven toolchain

## Summary

The project compiles for Java 25 (`java.version`, `maven.compiler.source/target` in the root `pom.xml`), but nothing chose the JDK: `./mvnw`, and so `scripts/dev.sh` / `scripts\dev.cmd`, used whatever `JAVA_HOME` or the first `java` on `PATH` was. With an older JDK every backend failed with `invalid target release: 25`, and the dev scripts did so in three windows at once.

The root `pom.xml` now runs `maven-toolchains-plugin:select-jdk-toolchain`. It finds the JDKs installed on the machine and selects a JDK 25 for the build; the compiler, surefire and `spring-boot:run` / `spring-boot:start` then use that JDK, while Maven itself may run on any Java 17 or later. No `toolchains.xml` is needed. The dev scripts are unchanged, since every `./mvnw` call they make now gets the right JDK.

A first version of this change (same PR) put a JDK search into `dev.sh` and `dev.ps1` instead (about 100 lines each). The toolchain does the same for every Maven invocation (scripts, `./mvnw` by hand, IntelliJ's Maven runner, CI) with about 15 lines of POM, so the scripts went back to their state from `docs/010-dev-start-scripts.md`.

## Configuration

```xml
<properties>
    <toolchain.jdk.version>[25,26)</toolchain.jdk.version>
    <maven-toolchains-plugin.version>3.3.0</maven-toolchains-plugin.version>
</properties>

<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-toolchains-plugin</artifactId>
    <version>${maven-toolchains-plugin.version}</version>
    <executions>
        <execution>
            <goals>
                <goal>select-jdk-toolchain</goal>   <!-- bound to validate -->
            </goals>
        </execution>
    </executions>
</plugin>
```

| Item                                  | Why                                                                                                                                                         |
|---------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `[25,26)`                             | Exactly 25, matching `java.version` and the IntelliJ project SDK (`.idea/misc.xml`: `corretto-25`). Change both together when moving to a new Java version |
| In `<plugins>` of the root POM        | Inherited by every module, so `-pl backends/<name>` builds also select it                                                                                   |
| Explicit version                      | Spring Boot 4.1.2 does not manage this plugin's version; without it Maven warns `'build.plugins.plugin.version' ... is missing` and picks the latest        |
| Minimum 3.2.0                         | JDK discovery and `select-jdk-toolchain` exist since 3.2.0                                                                                                  |

Where the plugin looks (from the 3.3.0 `ToolchainDiscoverer` class):

| Source                  | Locations                                                                                                                                         |
|-------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------|
| Current JDK             | The JDK Maven runs on, used if it matches                                                                                                         |
| Environment             | `JAVA_HOME` and `JAVA<n>_HOME` (e.g. `JAVA25_HOME`)                                                                                                |
| Under the home folder   | `~/.jdks` (IntelliJ downloads), `~/.sdkman`, `~/.asdf`, `~/.gradle` (Gradle toolchains), `~/.jbang`, scoop                                         |
| Linux                   | `/usr/lib/jvm`, `/usr/lib64/jvm`, `/opt/java`, `/opt/hostedtoolcache` (GitHub Actions)                                                             |
| macOS                   | `/Library/Java/JavaVirtualMachines`, `~/Library/Java/JavaVirtualMachines`                                                                          |
| Windows                 | `C:\Program Files\Java`, `\Eclipse Adoptium`, `\Amazon Corretto`, `\BellSoft`, `\Zulu`                                                              |

`PATH` is not searched. Results are cached in `~/.m2/discovered-jdk-toolchains-cache.xml`. To see what it finds:

```
./mvnw org.apache.maven.plugins:maven-toolchains-plugin:3.3.0:display-discovered-jdk-toolchains
```

If the JDK 25 is in a place the plugin does not search, set `JAVA_HOME` (or `JAVA25_HOME`) to it, or list it in `~/.m2/toolchains.xml`.

## Behaviour

| Situation                                          | Result                                                                                                                                                                  |
|----------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Maven runs on JDK 25                               | That JDK is used                                                                                                                                                        |
| Maven runs on JDK 17–24 or 26+, a JDK 25 installed | The JDK 25 compiles (`javac [forked ... release 25]`), runs the tests and runs the app                                                                                  |
| No JDK 25 found                                    | The build stops at `validate`, before anything compiles: `Cannot find matching toolchain definitions for the following toolchain types:{version=[25,26)}`              |
| Maven runs on Java 11 or older                     | Fails before the toolchain is chosen: the Spring Boot 4 Maven plugin needs Java 17 to load. `JAVA_HOME` must point to some Java 17+                                    |

## Linked Claude VM (`.vm/`, not in git)

The Claude desktop app's linked Linux VM has only a Java 11 runtime, no root access, and a home directory that is wiped after each session, so a JDK installed there does not persist. The repo folder is mounted into the VM and does persist, so the VM's JDK and Maven cache live in `.vm/` in the repo, which `.gitignore` excludes:

| Path               | Contents                                                                                                       |
|--------------------|----------------------------------------------------------------------------------------------------------------|
| `.vm/jdk-25/`      | Ubuntu OpenJDK 25, unpacked from the `.deb` packages without root (Linux binaries, about 330 MB)               |
| `.vm/maven/`       | Maven Wrapper distribution and local repository for VM builds                                                  |
| `.vm/env.sh`       | Sets `JAVA_HOME`, `MAVEN_USER_HOME`, `MAVEN_ARGS` and the VM proxy credentials; writes `.vm/settings.xml`      |
| `.vm/setup.sh`     | Re-creates `.vm/jdk-25/` from the Ubuntu packages if it is missing (`env.sh` calls it)                         |
| `.vm/README.md`    | Short version of this section                                                                                   |

Usage in the VM: `. .vm/env.sh && ./mvnw ...`, in the same command, because the proxy credentials change per command. VM commands, including background processes, are stopped after about 3 minutes, so long builds run in steps (`-pl <module>`); `.vm/maven/` keeps what was already downloaded. `CLAUDE.md` has a one-line pointer so later sessions use it. On Windows, `.vm/` is unused and can be deleted at any time.

## Changed files

| File                          | Change                                                                       |
|-------------------------------|------------------------------------------------------------------------------|
| `pom.xml`                     | `toolchain.jdk.version`, `maven-toolchains-plugin` with `select-jdk-toolchain` |
| `.gitignore`                  | `/.vm/`                                                                      |
| `README.md`, `CLAUDE.md`      | JDK 25 prerequisite and toolchain; VM note in `CLAUDE.md`                     |
| `scripts/dev.sh`, `dev.ps1`   | Unchanged compared to `main`                                                 |

## Verification

Run on Linux with JDK 21 and JDK 25 installed, Maven started on JDK 21 (`JAVA_HOME` and `PATH` set to JDK 21):

| Check                                                                                        | Result                                                                                                                                                                   |
|----------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `./mvnw clean install`                                                                       | `BUILD SUCCESS`, all 6 modules; `Selected JDK toolchain: JDK[/usr/lib/jvm/java-25-openjdk-amd64]` in each module; `Toolchain in maven-compiler-plugin` / `maven-surefire-plugin` = JDK 25; `javac [forked debug parameters release 25]`; 13 tests passed, logged as `using Java 25.0.4.1` |
| `./mvnw spring-boot:run -pl backends/stocks` (Docker Compose disabled; stocks needs none)     | `Starting StocksApplication using Java 25.0.4.1`; Maven JVM was `java-21-openjdk`, app JVM `java-25-openjdk`; `GET /api/stocks` returned the stock list                  |
| `./mvnw validate -N -Dtoolchain.jdk.version='[26,27)'` (no matching JDK)                     | Fails at `validate` with `Cannot find matching toolchain definitions ... {version=[26,27)}`                                                                              |
| Linked VM: `. .vm/env.sh && ./mvnw -v`                                                        | `Java version: 25.0.4.1, runtime: .../.vm/jdk-25`; Maven 3.9.16 downloaded into `.vm/maven/wrapper`                                                                       |
| Linked VM: `.vm/setup.sh` into an empty folder                                                | JDK 25 installed in 16 s, no links left pointing outside the JDK                                                                                                        |

To do on Windows: `mvnw.cmd clean install` and `scripts\dev.cmd`; each server window should log `Selected JDK toolchain: JDK[...25...]` and `Starting ...Application using Java 25`.
