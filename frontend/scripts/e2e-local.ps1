$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$frontendDir = Resolve-Path (Join-Path $scriptDir "..")
$repoRoot = Resolve-Path (Join-Path $frontendDir "..")
$backendDir = Join-Path $repoRoot "backend"
$logDir = Join-Path $repoRoot ".omx\logs"

New-Item -ItemType Directory -Force -Path $logDir | Out-Null

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

$backendProcess = $null
$frontendProcess = $null

try {
  Assert-PortFree -Port 8080
  Assert-PortFree -Port 3000

  $env:APP_AUTH_MOCK_LOGIN_ENABLED = "true"
  $env:APP_SYNC_GOOGLE_MOCK_ENABLED = "true"
  $env:APP_AI_ENABLED = "false"
  $env:APP_FRONTEND_URL = "http://localhost:3000"

  $backendProcess = Start-Process `
    -FilePath "powershell" `
    -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ".\gradlew.bat bootRun") `
    -WorkingDirectory $backendDir `
    -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $logDir "e2e-local-backend.out.log") `
    -RedirectStandardError (Join-Path $logDir "e2e-local-backend.err.log") `
    -PassThru

  Wait-HttpOk -Url "http://127.0.0.1:8080/actuator/health" -TimeoutSeconds 90

  $env:BACKEND_INTERNAL_URL = "http://127.0.0.1:8080"
  $env:NEXT_PUBLIC_API_BASE_URL = "http://localhost:8080"

  $frontendProcess = Start-Process `
    -FilePath "npm.cmd" `
    -ArgumentList @("run", "dev") `
    -WorkingDirectory $frontendDir `
    -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $logDir "e2e-local-frontend.out.log") `
    -RedirectStandardError (Join-Path $logDir "e2e-local-frontend.err.log") `
    -PassThru

  Wait-HttpOk -Url "http://127.0.0.1:3000/login" -TimeoutSeconds 90

  $env:PLAYWRIGHT_BASE_URL = "http://localhost:3000"
  $env:PLAYWRIGHT_API_URL = "http://localhost:8080"

  Push-Location $frontendDir
  try {
    npx playwright test
    if ($LASTEXITCODE -ne 0) {
      exit $LASTEXITCODE
    }
  } finally {
    Pop-Location
  }
} finally {
  Stop-StartedProcess -Process $frontendProcess
  Stop-StartedProcess -Process $backendProcess
}
