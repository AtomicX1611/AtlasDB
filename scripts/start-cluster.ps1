<#
.SYNOPSIS
    Start a 3-node AtlasDB cluster as separate JVM processes.

.DESCRIPTION
    Launches node-0, node-1, node-2 each as an independent JVM process on
    ports 50050, 50051, 50052. Each node writes its own log to logs/<nodeId>.log
    in the project root (the NodeLauncher handles this internally, so process
    lifetimes are independent of this PowerShell session).

    Prerequisites:
        1. Build the fat JAR first:
               mvn package -DskipTests
           This creates: target\atlasdb-jar-with-dependencies.jar

        2. Run this script from the project root:
               .\scripts\start-cluster.ps1

.NOTES
    PIDs are saved to scripts\cluster.pids for use by stop-cluster.ps1
    Logs are written to logs\node-{id}.log (managed by NodeLauncher itself)
#>

param(
    [int]    $Nodes    = 3,
    [int]    $BasePort = 50050,
    [string] $Host     = "localhost"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Jar         = Join-Path $ProjectRoot "target\atlasdb-jar-with-dependencies.jar"
$PidFile     = Join-Path $PSScriptRoot "cluster.pids"

if (-not (Test-Path $Jar)) {
    Write-Error @"
Fat JAR not found: $Jar
Build it first with:
    mvn package -DskipTests
"@
    exit 1
}

# ── Build peer list ───────────────────────────────────────────────────────────
$peerList = (0..($Nodes-1) | ForEach-Object { "node-$_`:$Host`:$($BasePort + $_)" }) -join ","

Write-Host ""
Write-Host "  AtlasDB Cluster — Phase 3 Multi-Process" -ForegroundColor Cyan
Write-Host "  ─────────────────────────────────────────" -ForegroundColor DarkGray
Write-Host "  Nodes  : $Nodes"
Write-Host "  Ports  : $BasePort – $($BasePort + $Nodes - 1)"
Write-Host "  Peers  : $peerList"
Write-Host ""

# ── Launch each node as a fully detached process ──────────────────────────────
# NodeLauncher writes its own logs to logs/<nodeId>.log — no pipe redirection
# needed, so the java process lifetime is NOT tied to this PowerShell session.

$processIds = @()
for ($i = 0; $i -lt $Nodes; $i++) {
    $nodeId = "node-$i"
    $port   = $BasePort + $i

    $proc = Start-Process -FilePath "javaw" `
        -ArgumentList @("-jar", $Jar, $nodeId, $Host, "$port", $peerList) `
        -WorkingDirectory $ProjectRoot `
        -PassThru `
        -WindowStyle Hidden

    $processIds += $proc.Id
    Write-Host "  ✓ Started $nodeId on port $port  (PID $($proc.Id))" -ForegroundColor Green
}

# ── Save PIDs ─────────────────────────────────────────────────────────────────
$processIds | Out-File -FilePath $PidFile -Encoding ascii
Write-Host ""
Write-Host "  All $Nodes nodes started." -ForegroundColor Yellow
Write-Host "  Logs: logs\node-0.log  logs\node-1.log  logs\node-2.log"
Write-Host "  PIDs: scripts\cluster.pids"
Write-Host ""
Write-Host "  Tail logs:     Get-Content logs\node-0.log -Wait" -ForegroundColor DarkGray
Write-Host "  Stop cluster:  .\scripts\stop-cluster.ps1" -ForegroundColor DarkGray
Write-Host ""
