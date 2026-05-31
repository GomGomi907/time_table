param(
  [switch]$Full,
  [switch]$RequireDocker,
  [switch]$SkipBackend,
  [switch]$SkipFrontend,
  [switch]$SkipSecretHygiene,
  [string]$ReportPath
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Resolve-Path (Join-Path $scriptDir "..")
$backendDir = Join-Path $repoRoot "backend"
$frontendDir = Join-Path $repoRoot "frontend"
$timestamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
$reportDir = Join-Path $repoRoot ".omx\reports"
New-Item -ItemType Directory -Force -Path $reportDir | Out-Null
if ([string]::IsNullOrWhiteSpace($ReportPath)) {
  $ReportPath = Join-Path $reportDir "release-readiness-$timestamp.md"
}
$jsonReportPath = [System.IO.Path]::ChangeExtension($ReportPath, ".json")
$gates = New-Object System.Collections.Generic.List[object]
$hasFailure = $false

function Add-Gate {
  param(
    [Parameter(Mandatory = $true)][string]$Id,
    [Parameter(Mandatory = $true)][string]$Category,
    [Parameter(Mandatory = $true)][string]$Status,
    [Parameter(Mandatory = $true)][string]$Evidence,
    [string]$Detail = ""
  )
  $script:gates.Add([pscustomobject]@{
    id = $Id
    category = $Category
    status = $Status
    evidence = $Evidence
    detail = $Detail
  }) | Out-Null
  if ($Status -in @("FAIL", "BLOCKED")) { $script:hasFailure = $true }
}

function Assert-LastExitCode {
  param([string]$Name)
  if ($LASTEXITCODE -ne 0) { throw "$Name failed with exit code $LASTEXITCODE" }
}

function Invoke-Gate {
  param(
    [Parameter(Mandatory = $true)][string]$Id,
    [Parameter(Mandatory = $true)][string]$Category,
    [Parameter(Mandatory = $true)][string]$Name,
    [Parameter(Mandatory = $true)][scriptblock]$Action
  )
  Write-Host "`n== [$Id] $Name ==" -ForegroundColor Cyan
  try {
    $evidence = & $Action
    Add-Gate -Id $Id -Category $Category -Status "PASS" -Evidence ($evidence -join "`n")
  } catch {
    Add-Gate -Id $Id -Category $Category -Status "FAIL" -Evidence $Name -Detail $_.Exception.Message
    Write-Host "[$Id] failed: $($_.Exception.Message)" -ForegroundColor Red
  }
}

function Get-ReleaseStatusLine {
  Push-Location $repoRoot
  try { return (git status --short --branch | Out-String).Trim() } finally { Pop-Location }
}

function Assert-NoTrackedSecrets {
  Push-Location $repoRoot
  try {
    $trackedSecrets = git ls-files |
      Where-Object { $_ -notmatch '(^|/)\.env\.example$' -and $_ -notmatch '(^|/)example[^/]*credentials[^/]*\.json$' } |
      Select-String -Pattern '(^|/)(\.env(\..*)?|client_secret_.*\.json|.*credentials.*\.json)$|api[_-]?key|secret' |
      ForEach-Object { $_.Line }
    if ($trackedSecrets) { throw "Tracked secret-like files found: $($trackedSecrets -join ', ')" }

    $dockerignore = Get-Content (Join-Path $repoRoot ".dockerignore") -Raw
    foreach ($requiredPattern in @(".env", ".env.*", "**/.env", "**/.env.*", "client_secret_*.json", "**/client_secret_*.json", "**/*credentials*.json", ".local")) {
      if (-not $dockerignore.Contains($requiredPattern)) { throw ".dockerignore is missing required exclusion: $requiredPattern" }
    }
    return "Tracked secret scan clean; Docker context exclusions present."
  } finally { Pop-Location }
}

function Test-SecretLikePath {
  param([string]$RelativePath)
  return $RelativePath -match '(^|[\\/])(\.env(\..*)?|client_secret_.*\.json|.*credentials.*\.json)$|api[_-]?key|secret'
}

function Invoke-WorkspaceSecretHygiene {
  $excludedDirs = @('.git', '.omx', '.local', 'node_modules', '.next', 'build', 'target', '.gradle', 'playwright-report', 'test-results', 'gemma4')
  $rootPrefix = $repoRoot.Path.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
  $suspicious = Get-ChildItem -LiteralPath $repoRoot -Recurse -Force -File -ErrorAction SilentlyContinue |
    Where-Object {
      $relative = $_.FullName.Substring($rootPrefix.Length)
      $parts = $relative -split '[\\/]'
      -not ($parts | Where-Object { $excludedDirs -contains $_ }) -and (Test-SecretLikePath $relative)
    } |
    ForEach-Object { $_.FullName.Substring($rootPrefix.Length).Replace('\','/') }

  $blocking = $suspicious | Where-Object { $_ -ne '.env.example' -and $_ -notmatch '(^|/)\.env\.example$' }
  if ($blocking) {
    throw "Secret-like files are present in the release workspace: $($blocking -join ', ')"
  }
  return "No blocking untracked/ignored secret-like files found outside approved local-only paths."
}

function Invoke-BackendGate {
  Push-Location $backendDir
  try { .\gradlew.bat test; Assert-LastExitCode "backend test"; return "Gradle backend tests passed." } finally { Pop-Location }
}

function Invoke-PostgresGate {
  Push-Location $backendDir
  try {
    .\gradlew.bat test --tests com.timetable.operator.common.persistence.PostgresFlywayMigrationTest
    Assert-LastExitCode "Postgres Flyway migration test"
    return "Postgres Flyway migration test command passed."
  } finally { Pop-Location }
}

function Invoke-FrontendGate {
  Push-Location $frontendDir
  try {
    npm run typecheck; Assert-LastExitCode "frontend typecheck"
    npm run build; Assert-LastExitCode "frontend build"
    npm run verify:hygiene; Assert-LastExitCode "frontend hygiene"
    return "Frontend typecheck, build, and hygiene passed."
  } finally { Pop-Location }
}

function Invoke-VisualGate {
  Push-Location $frontendDir
  try { npm run visual:local; Assert-LastExitCode "frontend visual local"; return "Visual local Playwright gate passed." } finally { Pop-Location }
}

function Assert-DockerAvailable {
  $docker = Get-Command docker -ErrorAction SilentlyContinue
  if (-not $docker) { throw "Docker is not installed or not on PATH." }
  docker version | Out-Null
  Assert-LastExitCode "docker version"
}

function Invoke-DockerBuildGate {
  Push-Location $repoRoot
  try { docker build -t timetable-release-verify:local .; Assert-LastExitCode "docker build"; return "Root Docker image build passed." } finally { Pop-Location }
}

function Write-ReleaseReports {
  $verdict = if ($hasFailure) { "NO-GO" } elseif ($Full) { "CONDITIONAL INTERNAL REHEARSAL" } else { "LOCAL GATES PASS" }
  $lines = @(
    "# Release readiness report",
    "",
    "Generated: $((Get-Date).ToUniversalTime().ToString('o'))",
    "Verdict: $verdict",
    "Full mode: $Full",
    "RequireDocker: $RequireDocker",
    "",
    "## Gate evidence",
    "",
    "| ID | Category | Status | Evidence | Detail |",
    "|---|---|---|---|---|"
  )
  foreach ($gate in $gates) {
    $evidence = ($gate.evidence -replace "`r?`n", "<br>") -replace '\|', '\/'
    $detail = ($gate.detail -replace "`r?`n", "<br>") -replace '\|', '\/'
    $lines += "| $($gate.id) | $($gate.category) | $($gate.status) | $evidence | $detail |"
  }
  Set-Content -Path $ReportPath -Value ($lines -join "`n") -Encoding UTF8
  [pscustomobject]@{
    generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    verdict = $verdict
    full = [bool]$Full
    requireDocker = [bool]$RequireDocker
    gates = $gates
  } | ConvertTo-Json -Depth 8 | Set-Content -Path $jsonReportPath -Encoding UTF8
  Write-Host "`nRelease report: $ReportPath" -ForegroundColor Cyan
  Write-Host "Release JSON: $jsonReportPath" -ForegroundColor Cyan
}

Add-Gate -Id "OPS-CTX" -Category "context" -Status "PASS" -Evidence (Get-ReleaseStatusLine)

if (-not $SkipSecretHygiene) {
  Invoke-Gate -Id "SEC-001" -Category "security" -Name "tracked and Docker-context secret hygiene" -Action { Assert-NoTrackedSecrets }
  Invoke-Gate -Id "SEC-002" -Category "security" -Name "workspace secret hygiene" -Action { Invoke-WorkspaceSecretHygiene }
}
if (-not $SkipBackend) { Invoke-Gate -Id "BE-001" -Category "backend" -Name "backend regression gate" -Action { Invoke-BackendGate } }
if (-not $SkipFrontend) { Invoke-Gate -Id "FE-001" -Category "frontend" -Name "frontend regression gate" -Action { Invoke-FrontendGate } }

if ($Full -or $RequireDocker) {
  try { Assert-DockerAvailable; Add-Gate -Id "DEP-001" -Category "deploy" -Status "PASS" -Evidence "Docker CLI available and daemon reachable." }
  catch {
    Add-Gate -Id "DEP-001" -Category "deploy" -Status $(if ($RequireDocker) { "BLOCKED" } else { "WARNING" }) -Evidence "Docker availability check" -Detail $_.Exception.Message
  }
}

if ($Full) {
  if (($gates | Where-Object { $_.id -eq "DEP-001" -and $_.status -eq "PASS" })) {
    Invoke-Gate -Id "DB-001" -Category "database" -Name "PostgreSQL Flyway migration gate" -Action { Invoke-PostgresGate }
    Invoke-Gate -Id "DEP-002" -Category "deploy" -Name "root Docker image build" -Action { Invoke-DockerBuildGate }
  } elseif ($RequireDocker) {
    Add-Gate -Id "DB-001" -Category "database" -Status "BLOCKED" -Evidence "PostgreSQL migration gate requires Docker/Testcontainers."
    Add-Gate -Id "DEP-002" -Category "deploy" -Status "BLOCKED" -Evidence "Docker image build requires Docker."
  }
  if (-not $SkipFrontend) { Invoke-Gate -Id "UX-001" -Category "visual" -Name "frontend visual local gate" -Action { Invoke-VisualGate } }
}

Write-ReleaseReports
if ($hasFailure) { exit 1 }
Write-Host "`nRelease verification completed." -ForegroundColor Green
