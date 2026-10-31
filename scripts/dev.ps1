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

Every server window is put into a Windows job object with KILL_ON_JOB_CLOSE, so
the whole process tree (cmd -> mvnw -> java, cmd -> npm -> node) dies when this
script ends for any reason: Ctrl+C in this window, closing this window, or the
script's PowerShell process being killed from Task Manager / taskkill.
Ctrl+C additionally runs `taskkill /T /F` on each tree for a tidy exit.

The Docker Compose containers (Valkey, Grafana, ...) are started by the
backends and intentionally keep running: `docker compose -p fs-web-springboot down`

.PARAMETER NoFrontend
Start only the backends.

.EXAMPLE
scripts\dev.cmd
.EXAMPLE
powershell -ExecutionPolicy Bypass -File scripts\dev.ps1 -NoFrontend
#>
[CmdletBinding()]
param(
    [switch]$NoFrontend
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

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
