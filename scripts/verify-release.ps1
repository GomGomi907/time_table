param(
  [switch]$Full,
  [switch]$SkipBackend,
  [switch]$SkipFrontend,
  [switch]$SkipSecretHygiene
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Resolve-Path (Join-Path $scriptDir "..")
$backendDir = Join-Path $repoRoot "backend"
$frontendDir = Join-Path $repoRoot "frontend"

function Invoke-Step {
  param(
    [Parameter(Mandatory = $true)][string]$Name,
    [Parameter(Mandatory = $true)][scriptblock]$Action
  )

  Write-Host "`n== $Name ==" -ForegroundColor Cyan
  & $Action
}

function Assert-LastExitCode {
  param([string]$Name)

  if ($LASTEXITCODE -ne 0) {
    throw "$Name failed with exit code $LASTEXITCODE"
  }
}

function Assert-NoTrackedSecrets {
  Push-Location $repoRoot
  try {
    $trackedSecrets = git ls-files |
      Where-Object { $_ -notmatch '(^|/)\.env\.example$' -and $_ -notmatch '(^|/)example[^/]*credentials[^/]*\.json$' } |
      Select-String -Pattern '(^|/)(\.env(\..*)?|client_secret_.*\.json|.*credentials.*\.json)$|api[_-]?key|secret' |
      ForEach-Object { $_.Line }

    if ($trackedSecrets) {
      throw "Tracked secret-like files found:`n$($trackedSecrets -join "`n")"
    }

    $dockerignore = Get-Content (Join-Path $repoRoot ".dockerignore") -Raw
    foreach ($requiredPattern in @(".env", ".env.*", "client_secret_*.json", "**/client_secret_*.json", ".local")) {
      if (-not $dockerignore.Contains($requiredPattern)) {
        throw ".dockerignore is missing required exclusion: $requiredPattern"
      }
    }

    Write-Host "Secret hygiene checks passed."
  } finally {
    Pop-Location
  }
}

function Invoke-BackendGate {
  Push-Location $backendDir
  try {
    .\gradlew.bat test
    Assert-LastExitCode "backend test"
  } finally {
    Pop-Location
  }
}

function Invoke-FrontendGate {
  Push-Location $frontendDir
  try {
    npm run typecheck
    Assert-LastExitCode "frontend typecheck"

    npm run build
    Assert-LastExitCode "frontend build"

    npm run verify:hygiene
    Assert-LastExitCode "frontend hygiene"

    if ($Full) {
      npm run visual:local
      Assert-LastExitCode "frontend visual local"
    }
  } finally {
    Pop-Location
  }
}

function Invoke-DockerGate {
  Push-Location $repoRoot
  try {
    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $docker) {
      throw "Docker is not installed or not on PATH; cannot run the full release Docker gate."
    }

    docker build -t timetable-release-verify:local .
    Assert-LastExitCode "docker build"
  } finally {
    Pop-Location
  }
}

Invoke-Step "release context" {
  Push-Location $repoRoot
  try {
    git status --short --branch
    Write-Host "Full mode: $Full"
  } finally {
    Pop-Location
  }
}

if (-not $SkipSecretHygiene) {
  Invoke-Step "secret and Docker context hygiene" { Assert-NoTrackedSecrets }
}

if (-not $SkipBackend) {
  Invoke-Step "backend regression gate" { Invoke-BackendGate }
}

if (-not $SkipFrontend) {
  Invoke-Step "frontend regression gate" { Invoke-FrontendGate }
}

if ($Full) {
  Invoke-Step "Docker image gate" { Invoke-DockerGate }
}

Write-Host "`nRelease verification wrapper completed." -ForegroundColor Green
