# 010 – One-command start/stop for the dev servers

## Summary

Development needs four processes: the `facade` (8080), `visitor` (8081) and `stocks` (8082) backends plus the Vite dev server (3000). Until now that meant four terminals. This change adds two launcher scripts under `scripts/` that start all of them with one command and, more importantly, **shut all of them down when the launcher stops** — on Ctrl+C, when the launcher is killed, or (on Linux/macOS) when one server dies on its own.

There is no pure-Maven way to do this: `spring-boot:run` blocks until the app exits, so a second `spring-boot:run` in the same build never starts, and `spring-boot:start` detaches the app with no console output or hot reload and cannot start Vite. Plain scripts are the simplest reliable option.

## What was added

| File                           | Platform                     | Role                                                                                         |
|--------------------------------|------------------------------|----------------------------------------------------------------------------------------------|
| `scripts/dev.sh`               | Linux, macOS, WSL            | Starts the four servers in one terminal with `[name]`-prefixed output; stops them all on exit |
| `scripts/dev.cmd`              | Windows (cmd, Explorer)      | Launcher: runs `dev.ps1` with `-ExecutionPolicy Bypass` so no policy change is needed          |
| `scripts/dev.ps1`              | Windows (PowerShell 5.1 / 7) | Starts each server in its own console window; stops them all when the script ends             |
| `.gitattributes`               | —                            | `*.sh` is always LF, `*.ps1` always CRLF (a CRLF `dev.sh` would not run under bash)           |
| `CLAUDE.md`, `README.md`       | —                            | Mention the scripts in the development instructions                                           |

Not a Maven goal, so the `./mvnw` commands in `CLAUDE.md` are unchanged; the scripts just run them.

## Usage

| Platform            | Start everything                                      | Backends only                 | Stop                                                            |
|---------------------|-------------------------------------------------------|-------------------------------|-----------------------------------------------------------------|
| Linux / macOS / WSL | `scripts/dev.sh`                                      | `scripts/dev.sh --no-frontend` | Ctrl+C in the terminal, or `kill <pid>` of the script           |
| Windows             | `scripts\dev.cmd` (double-click works too)            | `scripts\dev.cmd -NoFrontend`  | Ctrl+C in the launcher window, or close that window              |

Prerequisites are unchanged: Docker running (the backends start Valkey/Grafana through Docker Compose), `./mvnw install -DskipTests` once per machine (the scripts print a hint when `~/.m2` has no `fs-web-frontend` jar), `npm install` once in `frontend/`.

Git Bash on Windows is not supported by `dev.sh` (no `pgrep`); use `dev.cmd` there.

## How shutdown works

### `dev.sh` (bash)

| Trigger                                                        | What happens                                                                                                                                      |
|----------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------|
| Ctrl+C                                                         | The terminal sends SIGINT to the whole foreground process group, so Maven, the app JVMs and Vite begin their own shutdown; the script's `INT` trap then runs `shutdown`, which SIGTERMs anything left |
| `kill <pid>` (SIGTERM) or terminal closed (SIGHUP)             | Only the script gets the signal; its trap runs `shutdown`                                                                                         |
| One server exits by itself (port in use, compile error, ...)   | A 1-second watchdog loop notices, prints `<name> exited, stopping the other servers`, runs `shutdown` and exits with code 1                        |
| `shutdown`                                                     | SIGTERM to every server and all of its descendants, wait up to 15 s for a clean stop (Spring Boot's graceful shutdown, Vite), then SIGKILL stragglers |

Details worth knowing:

- Each server is started as `( cd dir && exec cmd ) 2>&1 | prefix name &`. The pipeline's first process **is** the server (because of `exec`), and its PID comes from `jobs -p %%`; the prefixing reader is a sibling, not a child, so it never confuses the process-tree walk.
- `spring-boot:run` forks the application JVM as a child of the Maven JVM. The watchdog loop re-snapshots each server's full descendant tree (`pgrep -P`, recursively) every second. That matters for Ctrl+C: if the Maven JVM dies from SIGINT before its child, the child is re-parented to PID 1 and can no longer be found through its parent — but it is still in the last snapshot and gets terminated.
- The readers ignore SIGINT so the servers' final log lines are still printed, and are killed at the very end if some missed process is still holding the pipe open (otherwise `wait` would hang).
- SIGKILL of the script itself cannot be trapped; the servers keep running in that one case (`pkill -f spring-boot:run`, `pkill -f vite`).

### `dev.ps1` (Windows)

| Trigger                                                         | What happens                                                                                                                                 |
|-----------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| Ctrl+C in the launcher window                                   | Read as a key (`TreatControlCAsInput`), the loop ends and `finally` runs `taskkill /PID <pid> /T /F` on each server's window                  |
| Launcher window closed, or `powershell.exe` killed (Task Manager, `taskkill`, logoff) | The **job object** does it: every server window was assigned to a Windows job with `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`; when the launcher process ends, the OS closes the last handle to the job and terminates every process in it (cmd → `mvnw.cmd` → java, cmd → npm → node) |

Details worth knowing:

- The job object is created through a small `Add-Type` C# shim (`FsWeb.KillOnCloseJob`): `CreateJobObject`, `SetInformationJobObject(JobObjectExtendedLimitInformation)`, `AssignProcessToJobObject`. Child processes inherit job membership automatically.
- Each server runs in its own window as `cmd /c "title <name> && <command> & echo [<name> exited] & pause"`, so if a server fails the window stays open with its last output instead of vanishing. Those windows are closed together with the rest on shutdown.
- `taskkill /F` is used because console Java processes ignore the "polite" `WM_CLOSE`; this means Spring Boot's shutdown hooks do not run on Windows. For dev servers that is acceptable (Valkey data lives in the container, not in the JVM).
- There is no "one server died → stop the others" behaviour on Windows; the dead server's window simply shows the error.

### Both

The Docker Compose containers (Valkey, Grafana LGTM, Kafka, MySQL, PostgreSQL, DynamoDB Local) are started by the backends with `lifecycle-management: start-only` and intentionally keep running after the scripts stop, exactly as before. `docker compose -p fs-web-springboot down` stops them.

## Verification

`dev.sh` was exercised against stand-in servers that mimic Maven forking a child JVM (a bash wrapper that forks `sleep`, forwards SIGTERM to it, and — in one scenario — dies on its own after 3 s). For each scenario the process table was checked for leftovers afterwards:

| Scenario                                     | Result                                                                  |
|----------------------------------------------|-------------------------------------------------------------------------|
| Ctrl+C (SIGINT to the process group)         | Script exits, 0 leftover processes (including the orphaned children)    |
| SIGTERM to the script only                   | Every wrapper logs `got TERM`, script exits, 0 leftovers                |
| One server exits on its own                  | `stocks exited, stopping the other servers`, exit code 1, 0 leftovers   |

The stand-in run found and fixed two real bugs before this was committed: orphaned grandchildren were missed when their parent died first (fixed by the per-second tree snapshot), and the script could hang in `wait` on the output readers (fixed by killing the readers last).

`dev.ps1` is written for Windows PowerShell 5.1 (what `dev.cmd` invokes) and PowerShell 7; it could not be run in the Linux environment this change was prepared in, so the first `scripts\dev.cmd` run on a Windows machine is the acceptance test: all four windows should open, and closing the launcher window (or Ctrl+C in it) should close all of them. No Java or frontend code changed, so `./mvnw clean install` output is unaffected by this change.
