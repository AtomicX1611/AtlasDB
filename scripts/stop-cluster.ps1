<#
.SYNOPSIS
    Stop all AtlasDB nodes started by start-cluster.ps1.
#>

$PidFile = Join-Path $PSScriptRoot "cluster.pids"

if (-not (Test-Path $PidFile)) {
    Write-Warning "No cluster.pids file found. Is the cluster running?"
    exit 0
}

$processIds = Get-Content $PidFile

Write-Host ""
Write-Host "  Stopping AtlasDB cluster..." -ForegroundColor Cyan

foreach ($pid in $processIds) {
    $pid = $pid.Trim()
    if ([string]::IsNullOrEmpty($pid)) { continue }
    try {
        Stop-Process -Id $pid -Force -ErrorAction Stop
        Write-Host "  ✓ Stopped PID $pid" -ForegroundColor Green
    } catch {
        Write-Host "  ✗ PID $pid not found (already stopped?)" -ForegroundColor DarkGray
    }
}

Remove-Item -Path $PidFile -Force -ErrorAction SilentlyContinue
Write-Host ""
Write-Host "  Cluster stopped." -ForegroundColor Yellow
Write-Host ""
