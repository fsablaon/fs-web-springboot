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
# The backends are built and run with JDK 25 (the project's java.version). The
# JDK is taken from, in order: --java-home, $JAVA_HOME (if it is 25), `java` on
# $PATH (if it is 25), then common install locations (~/.jdks, SDKMAN,
# /usr/lib/jvm, macOS JavaVirtualMachines, ~/opt). It is exported as JAVA_HOME
# for ./mvnw. The script stops with a message if no JDK 25 is found.
#
# Usage: scripts/dev.sh [--no-frontend] [--java-home <dir>]
#
# For Windows (cmd/PowerShell) use scripts\dev.cmd. Git Bash lacks pgrep, so use
# dev.cmd there as well.
#
# The Docker Compose containers (Valkey, Grafana, ...) are started by the
# backends and intentionally keep running: `docker compose -p fs-web-springboot down`

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 1

REQUIRED_JAVA=25

NO_FRONTEND=0
JAVA_HOME_ARG=""
while [ $# -gt 0 ]; do
    case "$1" in
        --no-frontend) NO_FRONTEND=1 ;;
        --java-home) [ -n "${2-}" ] || { echo "--java-home needs a directory" >&2; exit 2; }; JAVA_HOME_ARG=$2; shift ;;
        --java-home=*) JAVA_HOME_ARG=${1#--java-home=} ;;
        -h|--help) sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1 (try --help)" >&2; exit 2 ;;
    esac
    shift
done

if ! command -v pgrep >/dev/null 2>&1; then
    echo "[dev] pgrep is required (not available in Git Bash; use scripts\\dev.cmd on Windows)" >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# JDK selection
# ---------------------------------------------------------------------------

# Print the feature version (25 for 25.0.1, 1 for 1.8.0) of the JDK in $1.
# Fails if $1 is not a JDK home (needs bin/javac and the `release` file).
jdk_version() {
    [ -x "$1/bin/javac" ] && [ -f "$1/release" ] || return 1
    sed -n 's/^JAVA_VERSION="\([0-9]*\).*/\1/p' "$1/release"
}

# Succeeds and prints $1 if it is a JDK of the required version.
is_required_jdk() {
    [ -n "$1" ] && [ "$(jdk_version "$1" 2>/dev/null)" = "$REQUIRED_JAVA" ] && echo "$1"
}

# Home directory of the `java` on $PATH (symlinks resolved), or nothing.
path_java_home() {
    local bin
    bin=$(command -v java 2>/dev/null) || return 1
    if command -v readlink >/dev/null 2>&1 && readlink -f / >/dev/null 2>&1; then
        bin=$(readlink -f "$bin")
    fi
    echo "$(cd "$(dirname "$bin")/.." 2>/dev/null && pwd)"
}

# Print every JDK of the required version found in the usual install locations,
# newest-looking first. Globs that match nothing are skipped.
find_installed_jdks() {
    local dir
    if [ -x /usr/libexec/java_home ]; then                      # macOS
        dir=$(/usr/libexec/java_home -v "$REQUIRED_JAVA" 2>/dev/null) && is_required_jdk "$dir"
    fi
    for dir in \
        "$HOME"/.jdks/*"$REQUIRED_JAVA"* \
        "$HOME"/.sdkman/candidates/java/"$REQUIRED_JAVA"* \
        /usr/lib/jvm/*"$REQUIRED_JAVA"* \
        /usr/local/lib/jvm/*"$REQUIRED_JAVA"* \
        /opt/*jdk*"$REQUIRED_JAVA"* \
        /Library/Java/JavaVirtualMachines/*"$REQUIRED_JAVA"*/Contents/Home \
        "$HOME"/Library/Java/JavaVirtualMachines/*"$REQUIRED_JAVA"*/Contents/Home \
        "$HOME"/opt/jdk-"$REQUIRED_JAVA"* \
        "$HOME"/opt/jdk*"$REQUIRED_JAVA"*; do
        is_required_jdk "$dir"
    done | awk '!seen[$0]++' | sort -r
}

# "JDK 17" or "not a JDK (...)" for the directory $1, for messages.
describe_jdk() {
    local v
    if v=$(jdk_version "$1" 2>/dev/null) && [ -n "$v" ]; then echo "JDK $v"; else echo "not a JDK (no bin/javac or release file)"; fi
}

select_jdk() {
    local jdk
    if [ -n "$JAVA_HOME_ARG" ]; then
        jdk=$(is_required_jdk "$JAVA_HOME_ARG") || {
            echo "[dev] --java-home $JAVA_HOME_ARG is $(describe_jdk "$JAVA_HOME_ARG"), need JDK $REQUIRED_JAVA" >&2
            exit 1
        }
        echo "$jdk"; return
    fi
    if [ -n "${JAVA_HOME-}" ]; then
        if jdk=$(is_required_jdk "$JAVA_HOME"); then echo "$jdk"; return; fi
        echo "[dev] JAVA_HOME=$JAVA_HOME is $(describe_jdk "$JAVA_HOME"), need JDK $REQUIRED_JAVA; looking elsewhere" >&2
    fi
    if jdk=$(is_required_jdk "$(path_java_home)"); then echo "$jdk"; return; fi
    jdk=$(find_installed_jdks | head -n 1)
    if [ -n "$jdk" ]; then echo "$jdk"; return; fi

    cat >&2 <<EOF
[dev] no JDK $REQUIRED_JAVA found. Checked --java-home, JAVA_HOME, java on PATH and:
[dev]   ~/.jdks, ~/.sdkman/candidates/java, /usr/lib/jvm, /usr/local/lib/jvm, /opt,
[dev]   /Library/Java/JavaVirtualMachines, ~/Library/Java/JavaVirtualMachines, ~/opt
[dev] Install JDK $REQUIRED_JAVA, or point at one: scripts/dev.sh --java-home <dir>  (or export JAVA_HOME=<dir>)
EOF
    exit 1
}

JAVA_HOME=$(select_jdk) || exit 1
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
echo "[dev] using JDK $REQUIRED_JAVA: $JAVA_HOME ($("$JAVA_HOME/bin/java" -version 2>&1 | grep -v JAVA_TOOL_OPTIONS | head -n 1))"

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
