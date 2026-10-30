# 006 – Rename the `demo` backend to `facade`

The only backend module was renamed from `demo` to `facade`. No behaviour changed: same endpoints,
same configuration, same Docker Compose services. Only the module name, Java package, main class, Spring application name and documentation changed.

## What changed

| Before                                     | After                                        |
|--------------------------------------------|----------------------------------------------|
| `backends/demo/`                           | `backends/facade/`                           |
| Maven artifactId `org.fsweb:demo`          | Maven artifactId `org.fsweb:facade`          |
| `<module>demo</module>` in `backends/pom.xml` | `<module>facade</module>`                 |
| Java package `org.fsweb.demo`              | Java package `org.fsweb.facade`              |
| Logger `org.fsweb.demo` in `application*.yml` | Logger `org.fsweb.facade`                 |
| JAR `backends/demo/target/demo-0.0.1-SNAPSHOT.jar` | `backends/facade/target/facade-0.0.1-SNAPSHOT.jar` |
| `./mvnw spring-boot:run -pl backends/demo` | `./mvnw spring-boot:run -pl backends/facade` |
| Main class `FsWebApplication`              | `FacadeApplication`                          |
| Test class `FsWebApplicationTests`         | `FacadeApplicationTests`                     |
| `spring.application.name: fs-web-springboot` | `spring.application.name: facade` (service name in Grafana/OTel) |

Unchanged on purpose:

| Item                                    | Why                                                                                   |
|-----------------------------------------|---------------------------------------------------------------------------------------|
| `docs/001`–`docs/005`                   | Historical records; they describe the layout at the time they were written            |
| `backends/compose.yaml`                 | Shared by all backends, nothing module-specific in it                                 |

## Files touched

| File                                                        | Change                                               |
|-------------------------------------------------------------|------------------------------------------------------|
| `backends/demo/**` → `backends/facade/**`                   | `git mv` (history preserved)                         |
| `.../java/org/fsweb/demo/**` → `.../java/org/fsweb/facade/**` | `git mv` for `main` and `test` sources             |
| `backends/facade/pom.xml`                                   | `artifactId`, `name`, `description`                  |
| `backends/pom.xml`                                          | `<modules>` entry                                    |
| `*.java` (9 files)                                          | `package` and `import` statements                    |
| `FsWebApplication.java` → `FacadeApplication.java`          | `git mv` + class rename                              |
| `FsWebApplicationTests.java` → `FacadeApplicationTests.java` | `git mv` + class rename                             |
| `application.yml`, `application-prod.yml`                   | `logging.level.org.fsweb.facade`; `spring.application.name: facade` |
| `CLAUDE.md`, `README.md`                                    | Paths, commands, module tables, directory trees      |

## After pulling this change

- IntelliJ: reload the Maven project; run configurations that pointed at the `demo` module must be re-pointed to `facade`.
- A stale `backends/demo/target/` directory may remain on disk from earlier builds; it is ignored by git and can be deleted.

## Verification

| Check                                                | Result                                               |
|------------------------------------------------------|------------------------------------------------------|
| `grep -ri demo` outside `docs/00x` and `.idea`       | No matches left in sources, POMs, `CLAUDE.md`, `README.md` |
| `./mvnw clean install`                               | See below                                            |
| `./mvnw spring-boot:run -pl backends/facade`         | See below                                            |

The Maven build and the app run were done on Windows with `mvnw.cmd` (JDK 25); results are recorded in the commit that
follows the rename if they required fixes, otherwise the rename commit is the verified state.
