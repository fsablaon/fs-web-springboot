<#
.SYNOPSIS
Starts every dev server, each in its own console window, and shuts all of them
down when this script stops.

.DESCRIPTION
Servers started (from the project root):

  facade    mvnw.cmd spring-boot:run -pl backends/facade    port 8080
  visitor   mvnw.cmd spring-boot:run -pl backends/visitor   port 8081
  stocks    mvnw.cmd spring-boot:run -pl backends/stocks    port 8082
  frontend  npm run dev (in frontend\)                      port 3000

The backends are built and run with JDK 25 (the project's java.version). The
JDK is taken from, in order: -JavaHome, $env:JAVA_HOME (if it is 25), java.exe
on PATH (if it is 25), then common install locations (%USERPROFILE%\.jdks,
Program Files\Amazon Corretto, Java, Eclipse Adoptium, Microsoft, Zulu,
BellSoft, scoop). It is set as JAVA_HOME for the server windows, so mvnw.cmd
uses it. The script stops with a message if no JDK 25 is found.

Every server window is put into a Windows job object with KILL_ON_JOB_CLOSE, so
the whole process tree (cmd -> mvnw -> java, cmd -> npm -> node) dies when this
script ends for any reason: Ctrl+C in this window, closing this window, or the
script's PowerShell process being killed from Task Manager / taskkill.
Ctrl+C additionally runs `taskkill /T /F` on each tree for a tidy exit.

The Docker Compose containers (Valkey, Grafana, ...) are started by the
backends and intentionally keep running: `docker compose -p fs-web-springboot down`

.PARAMETER NoFrontend
Start only the backends.

.PARAMETER JavaHome
Home directory of the JDK 25 to use (overrides JAVA_HOME and auto-detection).

.EXAMPLE
scripts\dev.cmd
.EXAMPLE
powershell -ExecutionPolicy Bypass -File scripts\dev.ps1 -NoFrontend
.EXAMPLE
scripts\dev.cmd -JavaHome "C:\Program Files\Amazon Corretto\jdk25.0.1_8"
#>
[CmdletBinding()]
param(
    [switch]$NoFrontend,
    [string]$JavaHome
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$requiredJava = 25

# ---------------------------------------------------------------------------
# JDK selection
# ---------------------------------------------------------------------------

# Feature version (25 for 25.0.1, 1 for 1.8.0) of the JDK in $dir, or $null if
# $dir is not a JDK home (needs bin\javac.exe and the `release` file).
function Get-JdkVersion([string]$dir) {
    if (-not $dir) { return $null }
    $release = Join-Path $dir 'release'
    if (-not (Test-Path (Join-Path $dir 'bin\javac.exe')) -or -not (Test-Path $release)) { return $null }
    $m = Select-String -Path $release -Pattern '^JAVA_VERSION="(\d+)' | Select-Object -First 1
    if ($m) { return [int]$m.Matches[0].Groups[1].Value }
    return $null
}

function Test-RequiredJdk([string]$dir) {
    return (Get-JdkVersion $dir) -eq $requiredJava
}

# Home directory of the java.exe on PATH, or $null. The Oracle "javapath" shim
# directory is not a JDK home and is skipped by the `release` check.
function Get-PathJavaHome {
    $cmd = Get-Command java.exe -ErrorAction SilentlyContinue
    if (-not $cmd) { return $null }
    return (Split-Path (Split-Path $cmd.Source -Parent) -Parent)
}

# Directories that vendors and tools install JDKs under; every subdirectory is
# checked with Get-JdkVersion, so vendor-specific names do not matter.
function Join-IfSet([string]$base, [string]$child) {
    if ($base) { Join-Path $base $child } else { $null }
}
$jdkSearchRoots = @(
    (Join-IfSet $env:USERPROFILE '.jdks')                              # IntelliJ IDEA downloads
    (Join-IfSet $env:ProgramFiles 'Amazon Corretto')
    (Join-IfSet $env:ProgramFiles 'Java')
    (Join-IfSet $env:ProgramFiles 'Eclipse Adoptium')
    (Join-IfSet $env:ProgramFiles 'Microsoft')
    (Join-IfSet $env:ProgramFiles 'Zulu')
    (Join-IfSet $env:ProgramFiles 'BellSoft')
    (Join-IfSet $env:LOCALAPPDATA 'Programs\Eclipse Adoptium')
    (Join-IfSet $env:USERPROFILE 'scoop\apps')
) | Where-Object { $_ -and (Test-Path $_) }

function Find-InstalledJdks {
    foreach ($rootDir in $jdkSearchRoots) {
        Get-ChildItem -Path $rootDir -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object {
                # scoop keeps the active version in <app>\current
                $candidates = @($_.FullName, (Join-Path $_.FullName 'current'))
                foreach ($c in $candidates) { if (Test-RequiredJdk $c) { $c; break } }
            }
    }
}

function Select-Jdk {
    if ($JavaHome) {
        if (Test-RequiredJdk $JavaHome) { return (Resolve-Path $JavaHome).Path }
        $found = Get-JdkVersion $JavaHome
        if ($null -eq $found) { $found = 'no JDK' }
        throw "-JavaHome $JavaHome is not a JDK $requiredJava (found: $found)"
    }
    if ($env:JAVA_HOME) {
        if (Test-RequiredJdk $env:JAVA_HOME) { return $env:JAVA_HOME }
        $found = Get-JdkVersion $env:JAVA_HOME
        if ($null -eq $found) { $found = '?' }
        Write-Host "[dev] JAVA_HOME=$env:JAVA_HOME is JDK $found, not $requiredJava; looking for a JDK $requiredJava elsewhere"
    }
    $pathJdk = Get-PathJavaHome
    if (Test-RequiredJdk $pathJdk) { return $pathJdk }
    $installed = @(Find-InstalledJdks)
    if ($installed.Count -gt 0) { return $installed[0] }

    $roots = ($jdkSearchRoots | ForEach-Object { "[dev]   $_" }) -join "`n"
    throw @"
no JDK $requiredJava found. Checked -JavaHome, JAVA_HOME, java.exe on PATH and:
$roots
[dev] Install JDK $requiredJava, or point at one: scripts\dev.cmd -JavaHome <dir>  (or set JAVA_HOME=<dir>)
"@
}

try {
    $jdk = Select-Jdk
}
catch {
    Write-Host "[dev] $($_.Exception.Message)"
    exit 1
}
$env:JAVA_HOME = $jdk                       # inherited by the server windows; mvnw.cmd uses it
$env:Path = "$jdk\bin;$env:Path"
# Version details from the `release` file (java -version prints to stderr, which
# $ErrorActionPreference = 'Stop' turns into an error under 2>&1 in PowerShell 5.1).
$releaseInfo = @{}
Get-Content (Join-Path $jdk 'release') | ForEach-Object {
    if ($_ -match '^(\w+)="?([^"]*)"?$') { $releaseInfo[$matches[1]] = $matches[2] }
}
Write-Host "[dev] using JDK ${requiredJava}: $jdk ($($releaseInfo['IMPLEMENTOR']) $($releaseInfo['JAVA_VERSION']))"

# ---------------------------------------------------------------------------
# Job object: every process assigned to it (and their children) is terminated
# by the OS when the last handle to the job closes, i.e. when this process ends.
# ---------------------------------------------------------------------------
if (-not ('FsWeb.KillOnCloseJob' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;

namespace FsWeb
{
    public static class KillOnCloseJob
    {
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        static extern IntPtr CreateJobObject(IntPtr attributes, string name);

        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool SetInformationJobObject(IntPtr job, int infoClass, IntPtr info, uint length);

        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);

        [StructLayout(LayoutKind.Sequential)]
        struct JOBOBJECT_BASIC_LIMIT_INFORMATION
        {
            public long PerProcessUserTimeLimit;
            public long PerJobUserTimeLimit;
            public uint LimitFlags;
            public UIntPtr MinimumWorkingSetSize;
            public UIntPtr MaximumWorkingSetSize;
            public uint ActiveProcessLimit;
            public UIntPtr Affinity;
            public uint PriorityClass;
            public uint SchedulingClass;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct IO_COUNTERS
        {
            public ulong ReadOperationCount, WriteOperationCount, OtherOperationCount;
            public ulong ReadTransferCount, WriteTransferCount, OtherTransferCount;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct JOBOBJECT_EXTENDED_LIMIT_INFORMATION
        {
            public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation;
            public IO_COUNTERS IoInfo;
            public UIntPtr ProcessMemoryLimit, JobMemoryLimit, PeakProcessMemoryUsed, PeakJobMemoryUsed;
        }

        const uint JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000;
        const int JobObjectExtendedLimitInformation = 9;

        static IntPtr job = IntPtr.Zero;   // never closed on purpose: closes with the process

        public static void Init()
        {
            if (job != IntPtr.Zero) return;
            job = CreateJobObject(IntPtr.Zero, null);
            if (job == IntPtr.Zero) throw new Win32Exception();

            var info = new JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
            info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
            int size = Marshal.SizeOf(typeof(JOBOBJECT_EXTENDED_LIMIT_INFORMATION));
            IntPtr buffer = Marshal.AllocHGlobal(size);
            try
            {
                Marshal.StructureToPtr(info, buffer, false);
                if (!SetInformationJobObject(job, JobObjectExtendedLimitInformation, buffer, (uint)size))
                    throw new Win32Exception();
            }
            finally
            {
                Marshal.FreeHGlobal(buffer);
            }
        }

        public static void Add(IntPtr processHandle)
        {
            if (!AssignProcessToJobObject(job, processHandle)) throw new Win32Exception();
        }
    }
}
'@
}
[FsWeb.KillOnCloseJob]::Init()

# ---------------------------------------------------------------------------
# Servers
# ---------------------------------------------------------------------------
$servers = @(
    @{ Name = 'facade';  Dir = $root; Cmd = 'mvnw.cmd spring-boot:run -pl backends/facade' }
    @{ Name = 'visitor'; Dir = $root; Cmd = 'mvnw.cmd spring-boot:run -pl backends/visitor' }
    @{ Name = 'stocks';  Dir = $root; Cmd = 'mvnw.cmd spring-boot:run -pl backends/stocks' }
)
if (-not $NoFrontend) {
    $servers += @{ Name = 'frontend'; Dir = (Join-Path $root 'frontend'); Cmd = 'npm run dev' }
}

if (-not (Test-Path (Join-Path $env:USERPROFILE '.m2\repository\org\fsweb\fs-web-frontend'))) {
    Write-Host '[dev] hint: the backends need the parent POM and the fs-web-frontend jar in ~\.m2.'
    Write-Host '[dev]       First time on this machine run: mvnw.cmd install -DskipTests'
}

$started = @()   # @{ Name; Process }

function Stop-Servers {
    foreach ($s in $started) {
        if (-not $s.Process.HasExited) {
            Write-Host "[dev] stopping $($s.Name) (pid $($s.Process.Id))"
            # /T = whole tree (cmd -> mvnw/npm -> java/node). Console java apps ignore the
            # polite WM_CLOSE, so /F is the only option that actually stops them.
            & taskkill.exe /PID $s.Process.Id /T /F *> $null
        }
    }
    Write-Host '[dev] all servers stopped'
}

try {
    foreach ($s in $servers) {
        Write-Host "[dev] starting $($s.Name): $($s.Cmd)"
        # The window stays open after the server exits so its last output (e.g. a
        # port-in-use error) can be read; it is closed with the others on shutdown.
        $cmdLine = "title $($s.Name) && $($s.Cmd) & echo. & echo [$($s.Name) exited] & pause"
        $p = Start-Process -FilePath 'cmd.exe' -ArgumentList "/c `"$cmdLine`"" -WorkingDirectory $s.Dir -PassThru
        [FsWeb.KillOnCloseJob]::Add($p.Handle)
        $started += @{ Name = $s.Name; Process = $p }
    }

    Write-Host '[dev] facade -> :8080  visitor -> :8081  stocks -> :8082  frontend -> http://localhost:3000'
    Write-Host '[dev] press Ctrl+C in this window (or close it) to stop all servers'

    $console = -not [Console]::IsInputRedirected
    if ($console) { [Console]::TreatControlCAsInput = $true }

    while ($true) {
        if ($console -and [Console]::KeyAvailable) {
            $key = [Console]::ReadKey($true)
            if ($key.Key -eq 'C' -and ($key.Modifiers -band [ConsoleModifiers]::Control)) { break }
        }
        Start-Sleep -Milliseconds 250
    }
}
finally {
    if ($console) { [Console]::TreatControlCAsInput = $false }
    Write-Host
    Write-Host '[dev] shutting down...'
    Stop-Servers
}
