param(
  [string[]]$PlaywrightArgs = @()
)

$ErrorActionPreference = "Stop"
trap {
  Write-Error $_
  exit 1
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$frontendDir = Resolve-Path (Join-Path $scriptDir "..")
$repoRoot = Resolve-Path (Join-Path $frontendDir "..")
$backendDir = Join-Path $repoRoot "backend"
$logDir = Join-Path $repoRoot ".omx\logs"
$e2eDataDir = Join-Path $repoRoot ".omx\e2e-data"

New-Item -ItemType Directory -Force -Path $logDir | Out-Null
New-Item -ItemType Directory -Force -Path $e2eDataDir | Out-Null

function Wait-HttpOk {
  param(
    [Parameter(Mandatory = $true)][string]$Url,
    [int]$TimeoutSeconds = 60
  )

  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  do {
    try {
      Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 3 | Out-Null
      return
    } catch {
      Start-Sleep -Seconds 1
    }
  } while ((Get-Date) -lt $deadline)

  throw "Timed out waiting for $Url"
}

function Stop-StartedProcess {
  param([System.Diagnostics.Process]$Process)

  if ($null -eq $Process -or $Process.HasExited) {
    return
  }

  $children = Get-CimInstance Win32_Process -Filter "ParentProcessId = $($Process.Id)" -ErrorAction SilentlyContinue
  foreach ($child in $children) {
    try {
      Stop-StartedProcess -Process (Get-Process -Id $child.ProcessId -ErrorAction SilentlyContinue)
    } catch {
      Write-Verbose "Failed to stop child process $($child.ProcessId): $_"
    }
  }

  Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
}

function Assert-PortFree {
  param([Parameter(Mandatory = $true)][int]$Port)

  $listeners = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique

  if ($listeners) {
    throw "Port $Port is already in use by PID(s): $($listeners -join ', '). Stop that process or set a different local port before running e2e:local."
  }
}

function Get-BindablePort {
  param([string]$EnvValue)

  if (-not [string]::IsNullOrWhiteSpace($EnvValue)) {
    return [int]$EnvValue
  }

  return [int](& node -e "const s=require('net').createServer();s.listen(0,'127.0.0.1',()=>{console.log(s.address().port);s.close();});")
}

function Assert-CommandSucceeded {
  param([string]$Name)

  if ($LASTEXITCODE -ne 0) {
    throw "$Name failed with exit code $LASTEXITCODE"
  }
}

$backendProcess = $null
$frontendProcess = $null
$backendPort = Get-BindablePort -EnvValue $env:E2E_BACKEND_PORT
$frontendPort = Get-BindablePort -EnvValue $env:E2E_FRONTEND_PORT
if ($frontendPort -eq $backendPort) {
  $frontendPort = Get-BindablePort -EnvValue $null
}

try {
  Assert-PortFree -Port $backendPort
  Assert-PortFree -Port $frontendPort

  $env:APP_AUTH_MOCK_LOGIN_ENABLED = "true"
  $env:APP_SYNC_GOOGLE_MOCK_ENABLED = "true"
  $env:APP_AI_ENABLED = "false"
  $env:SERVER_PORT = "$backendPort"
  $env:APP_FRONTEND_URL = "http://localhost:$frontendPort"
  $e2eDbName = "timetable-e2e-$((Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmss'))"
  $e2eDbPath = (Join-Path $e2eDataDir $e2eDbName).Replace("\", "/")
  $env:APP_DB_URL = "jdbc:h2:file:$e2eDbPath;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
  $env:APP_DB_USERNAME = "sa"
  $env:APP_DB_PASSWORD = ""

  $backendProcess = Start-Process `
    -FilePath "powershell" `
    -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ".\gradlew.bat bootRun") `
    -WorkingDirectory $backendDir `
    -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $logDir "e2e-local-backend.out.log") `
    -RedirectStandardError (Join-Path $logDir "e2e-local-backend.err.log") `
    -PassThru

  Wait-HttpOk -Url "http://127.0.0.1:$backendPort/actuator/health" -TimeoutSeconds 90

  $env:BACKEND_INTERNAL_URL = "http://127.0.0.1:$backendPort"
  $env:NEXT_PUBLIC_API_BASE_URL = "http://localhost:$backendPort"
  $env:HOSTNAME = "127.0.0.1"
  $env:NEXT_PUBLIC_ENABLE_VISUAL_LOGIN = "true"

  Push-Location $frontendDir
  try {
    npm run build
    Assert-CommandSucceeded "frontend production build"
  } finally {
    Pop-Location
  }

  $frontendProcess = Start-Process `
    -FilePath "npm.cmd" `
    -ArgumentList @("run", "start", "--", "--hostname", "127.0.0.1", "--port", "$frontendPort") `
    -WorkingDirectory $frontendDir `
    -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $logDir "e2e-local-frontend.out.log") `
    -RedirectStandardError (Join-Path $logDir "e2e-local-frontend.err.log") `
    -PassThru

  Wait-HttpOk -Url "http://127.0.0.1:$frontendPort/login" -TimeoutSeconds 90

  $env:PLAYWRIGHT_BASE_URL = "http://localhost:$frontendPort"
  $env:PLAYWRIGHT_API_URL = "http://localhost:$backendPort"

  Push-Location $frontendDir
  try {
    npx playwright test @PlaywrightArgs
    if ($LASTEXITCODE -ne 0) {
      throw "Playwright failed with exit code $LASTEXITCODE"
    }
  } finally {
    Pop-Location
  }
} finally {
  Stop-StartedProcess -Process $frontendProcess
  Stop-StartedProcess -Process $backendProcess
}
