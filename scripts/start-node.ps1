<#
.SYNOPSIS
    Start a single AtlasDB node (foreground — logs to console).

.DESCRIPTION
    Use this to start individual nodes in separate terminals, giving you
    real-time log visibility per node.

.PARAMETER NodeId
    Node identifier (default: node-0)

.PARAMETER Host
    Bind address (default: localhost)

.PARAMETER Port
    gRPC listen port (default: 50050)

.PARAMETER Peers
    Comma-separated peer list: id:host:port,id:host:port,...
    Default: 3-node local cluster on ports 50050-50052

.EXAMPLE
    # Terminal 1
    .\scripts\start-node.ps1 -NodeId node-0 -Port 50050

    # Terminal 2
    .\scripts\start-node.ps1 -NodeId node-1 -Port 50051

    # Terminal 3
    .\scripts\start-node.ps1 -NodeId node-2 -Port 50052
#>

param(
    [string] $NodeId = "node-0",
    [string] $Host   = "localhost",
    [int]    $Port   = 50050,
    [string] $Peers  = "node-0:localhost:50050,node-1:localhost:50051,node-2:localhost:50052"
)

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Jar         = Join-Path $ProjectRoot "target\atlasdb-jar-with-dependencies.jar"

if (-not (Test-Path $Jar)) {
    Write-Error "Fat JAR not found. Run: mvn package -DskipTests"
    exit 1
}

Write-Host ""
Write-Host "  Starting AtlasDB node: $NodeId  ($Host`:$Port)" -ForegroundColor Cyan
Write-Host "  Peers: $Peers" -ForegroundColor DarkGray
Write-Host "  Press Ctrl+C to stop."
Write-Host ""

java `
    "-Djava.util.logging.SimpleFormatter.format=[%1`$tT.%1`$tL] [%-7`$4s] %5`$s%n" `
    -jar $Jar `
    $NodeId $Host $Port $Peers
