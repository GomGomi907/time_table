param(
  [string]$ImageTag = "timetable-release-smoke:local"
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) "..")
Push-Location $repoRoot
try {
  docker version | Out-Null
  docker build -t $ImageTag .
  if ($LASTEXITCODE -ne 0) { throw "docker build failed" }
  Write-Host "Container image built: $ImageTag"
  Write-Host "Full disposable Postgres/container runtime smoke is intentionally CI-owned; run scripts/verify-release.ps1 -Full -RequireDocker before beta promotion."
} finally {
  Pop-Location
}
