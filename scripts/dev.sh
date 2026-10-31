#!/usr/bin/env bash
# Starts every dev server in one terminal and shuts all of them down when this
# script stops.
#
#   facade   ./mvnw spring-boot:run -pl backends/facade    port 8080
#   visitor  ./mvnw spring-boot:run -pl backends/visitor   port 8081
#   stocks   ./mvnw spring-boot:run -pl backends/stocks    port 8082
#   frontend npm run dev (in frontend/)                     port 3000
#
# Output of each server is prefixed with its name. The servers are stopped when:
#   - you press Ctrl+C in this terminal
#   - this script receives SIGTERM or SIGHUP (kill <pid>, terminal closed)
#   - one of the servers exits on its own (port in use, build error, ...)
# Each server gets SIGTERM first (Spring Boot and Vite shut down cleanly), then
# SIGKILL after 15 s for anything still alive. SIGKILL of this script itself
# cannot be caught; in that case stop the leftovers with `kill` by hand.
#
# Usage: scripts/dev.sh [--no-frontend]
#
# For Windows (cmd/PowerShell) use scripts\dev.cmd. Git Bash lacks pgrep, so use
# dev.cmd there as well.
#
# The Docker Compose containers (Valkey, Grafana, ...) are started by the
# backends and intentionally keep running: `docker compose -p fs-web-springboot down`

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 1

NO_FRONTEND=0
for arg in "$@"; do
    case "$arg" in
        --no-frontend) NO_FRONTEND=1 ;;
        -h|--help) sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $arg (try --help)" >&2; exit 2 ;;
    esac
done

if ! command -v pgrep >/dev/null 2>&1; then
    echo "[dev] pgrep is required (not available in Git Bash; use scripts\\dev.cmd on Windows)" >&2
    exit 1
fi

if [ ! -d "${HOME}/.m2/repository/org/fsweb/fs-web-frontend" ]; then
    echo "[dev] hint: the backends need the parent POM and the fs-web-frontend jar in ~/.m2."
    echo "[dev]       First time on this machine run: ./mvnw install -DskipTests"
fi

names=()      # server names
pids=()       # server PIDs (mvnw -> java, npm)
readers=()    # PIDs of the output-prefixing readers
tree=()       # last known PIDs of the servers and all their descendants

# Copy stdin to stdout with "[name] " in front of each line. Runs as the last
# element of a pipeline (its own subshell). It ignores Ctrl+C so the servers'
# own shutdown messages still get printed; it exits when the pipe closes.
prefix() {
    trap '' INT
    while IFS= read -r line; do printf '[%s] %s\n' "$1" "$line"; done
}

# Run "$@" in directory $2 with its output tagged $1, and remember the PIDs.
start() {
    local name=$1 dir=$2
    shift 2
    echo "[dev] starting $name: $*"
    ( cd "$dir" && exec "$@" ) 2>&1 | prefix "$name" &
    readers+=("$!")                 # last process of the pipeline = the reader
    pids+=("$(jobs -p %%)")         # first process of the pipeline = the server
    names+=("$name")
}

# Print the PIDs of every descendant of $1 (children, grandchildren, ...).
descendants() {
    local child
    for child in $(pgrep -P "$1" 2>/dev/null); do
        echo "$child"
        descendants "$child"
    done
}

# Refresh $tree. Done every second while running: if Ctrl+C kills a Maven JVM
# before the forked app JVM, the app is orphaned and no longer findable through
# its parent, but it is still in the last snapshot.
snapshot_tree() {
    local pid
    tree=()
    for pid in "${pids[@]}"; do
        tree+=("$pid" $(descendants "$pid"))
    done
}

# Return 0 if any of the given PIDs is still alive.
any_alive() {
    local pid
    for pid in "$@"; do
        kill -0 "$pid" 2>/dev/null && return 0
    done
    return 1
}

# Terminate every server and its process tree, then exit with code $1.
shutdown() {
    local code=${1:-0}
    trap - INT TERM HUP EXIT
    echo
    echo "[dev] shutting down..."

    local victims=("${tree[@]}") pid
    for pid in "${pids[@]}"; do
        victims+=($(descendants "$pid"))   # anything spawned since the last snapshot
    done

    if [ ${#victims[@]} -gt 0 ]; then
        kill -TERM "${victims[@]}" 2>/dev/null
        local i
        for i in $(seq 1 30); do                     # up to 15 s for a clean shutdown
            any_alive "${victims[@]}" || break
            sleep 0.5
        done
        if any_alive "${victims[@]}"; then
            echo "[dev] some processes did not stop in time, killing them"
            kill -KILL "${victims[@]}" 2>/dev/null
        fi
    fi

    # The readers exit by themselves once every writer is gone; give them a
    # moment, then remove any that are still held open by a process we missed.
    for i in $(seq 1 6); do
        any_alive "${readers[@]}" || break
        sleep 0.5
    done
    kill -KILL "${readers[@]}" 2>/dev/null
    wait 2>/dev/null
    echo "[dev] all servers stopped"
    exit "$code"
}

trap 'shutdown 130' INT
trap 'shutdown 143' TERM HUP
trap 'shutdown'     EXIT

start facade  "$ROOT" ./mvnw spring-boot:run -pl backends/facade
start visitor "$ROOT" ./mvnw spring-boot:run -pl backends/visitor
start stocks  "$ROOT" ./mvnw spring-boot:run -pl backends/stocks
if [ "$NO_FRONTEND" -eq 0 ]; then
    start frontend "$ROOT/frontend" npm run dev
fi

echo "[dev] facade -> :8080  visitor -> :8081  stocks -> :8082  frontend -> http://localhost:3000"
echo "[dev] press Ctrl+C to stop all servers"

# Watch the servers; if one dies, take the others down with it.
while :; do
    for i in "${!pids[@]}"; do
        if ! kill -0 "${pids[$i]}" 2>/dev/null; then
            echo "[dev] ${names[$i]} exited, stopping the other servers"
            shutdown 1
        fi
    done
    snapshot_tree
    sleep 1
done
