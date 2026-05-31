param(
  [Parameter(Mandatory = $true)][string]$BaseUrl,
  [string]$OutputPath
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) "..")
$timestamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
  $dir = Join-Path $repoRoot ".omx\reports"
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  $OutputPath = Join-Path $dir "deployed-smoke-$timestamp.json"
}
$base = $BaseUrl.TrimEnd('/')
$checks = New-Object System.Collections.Generic.List[object]
$failed = $false

function Add-Check($Path, $Status, $Detail) {
  $script:checks.Add([pscustomobject]@{ path = $Path; status = $Status; detail = $Detail }) | Out-Null
  if ($Status -eq "FAIL") { $script:failed = $true }
}

function Invoke-SafeGet($Path, [int[]]$AllowedStatus = @(200)) {
  try {
    $response = Invoke-WebRequest -UseBasicParsing -Uri "$base$Path" -TimeoutSec 20 -MaximumRedirection 5
    $statusCode = [int]$response.StatusCode
    $body = [string]$response.Content
  } catch [System.Net.WebException] {
    if ($_.Exception.Response -ne $null) {
      $statusCode = [int]$_.Exception.Response.StatusCode
      $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
      $body = $reader.ReadToEnd()
      $reader.Dispose()
    } else {
      Add-Check $Path "FAIL" $_.Exception.Message
      return
    }
  } catch {
    Add-Check $Path "FAIL" $_.Exception.Message
    return
  }

  if ($AllowedStatus -notcontains $statusCode) {
    Add-Check $Path "FAIL" "HTTP $statusCode, expected $($AllowedStatus -join '/')"
    return
  }
  if ($body -match 'GOOGLE_CLIENT_SECRET|APP_GEMINI_API_KEY|chainOfThought|validationTrace|missingFields|ambiguousFields') {
    Add-Check $Path "FAIL" "Internal or secret-like text leaked in response body."
    return
  }
  Add-Check $Path "PASS" "HTTP $statusCode"
}

Invoke-SafeGet "/login" @(200)
Invoke-SafeGet "/actuator/health" @(200)
Invoke-SafeGet "/api/auth/session" @(200,401,403)
Invoke-SafeGet "/auth/callback?status=error&reason=smoke" @(200)
Invoke-SafeGet "/api/auth/mock/login" @(400,401,403,404,405)

[pscustomobject]@{
  generatedAt = (Get-Date).ToUniversalTime().ToString('o')
  baseUrl = $base
  checks = $checks
} | ConvertTo-Json -Depth 6 | Set-Content -Path $OutputPath -Encoding UTF8
Write-Host "Deployed smoke report: $OutputPath"
if ($failed) { exit 1 }
