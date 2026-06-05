param(
  [string]$BaseUrl = "http://127.0.0.1:8080",
  [ValidateSet("agent", "chat")]
  [string]$Endpoint = "agent",
  [string]$Message = "오늘 내일 연차를 썼다. 일정을 수정해라.",
  [string]$OutPath = ""
)

$ErrorActionPreference = "Stop"

function New-ProbeOutputPath {
  $timestamp = Get-Date -Format "yyyyMMddTHHmmss"
  $dir = Join-Path (Get-Location) ".omx\reports"
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  return (Join-Path $dir "llm-live-probe-$timestamp.json")
}

function Invoke-MockLogin {
  param(
    [string]$RootUrl,
    [Microsoft.PowerShell.Commands.WebRequestSession]$Session
  )

  $email = "llm-probe-$(Get-Date -Format "yyyyMMddHHmmss")@time-table.test"
  $name = [Uri]::EscapeDataString("LLM Probe")
  $loginUrl = "$RootUrl/api/auth/mock/login?email=$email&name=$name&connectGoogle=false&writeCapable=false"

  try {
    Invoke-WebRequest -Uri $loginUrl -WebSession $Session -MaximumRedirection 0 | Out-Null
  } catch {
    $response = $_.Exception.Response
    $statusCode = if ($response -and $response.StatusCode) { [int]$response.StatusCode } else { $null }
    if ($statusCode -ne 302 -and $statusCode -ne 303) {
      throw
    }
  }

  return $email
}

if ([string]::IsNullOrWhiteSpace($OutPath)) {
  $OutPath = New-ProbeOutputPath
}

$BaseUrl = $BaseUrl.TrimEnd("/")
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$email = Invoke-MockLogin -RootUrl $BaseUrl -Session $session

$csrf = Invoke-RestMethod -Uri "$BaseUrl/api/auth/csrf" -WebSession $session
$headers = @{
  $csrf.headerName = $csrf.token
}

if ($Endpoint -eq "agent") {
  $requestPath = "/api/agent/reschedule"
  $bodyObject = @{
    triggerType = "manual_request"
    reason = $Message
  }
} else {
  $requestPath = "/api/chat/command"
  $bodyObject = @{
    message = $Message
  }
}

$body = $bodyObject | ConvertTo-Json -Depth 8
$startedAt = Get-Date -Format "o"
$response = Invoke-RestMethod `
  -Uri "$BaseUrl$requestPath" `
  -Method POST `
  -WebSession $session `
  -Headers $headers `
  -ContentType "application/json" `
  -Body $body
$finishedAt = Get-Date -Format "o"

$artifact = [ordered]@{
  startedAt = $startedAt
  finishedAt = $finishedAt
  baseUrl = $BaseUrl
  endpoint = $Endpoint
  mockUserEmail = $email
  message = $Message
  requestPath = $requestPath
  response = $response
}

$artifact | ConvertTo-Json -Depth 40 | Set-Content -Path $OutPath -Encoding UTF8
Write-Host "LLM live probe saved: $OutPath"
Write-Host "Endpoint: $Endpoint"
Write-Host "Message: $Message"
