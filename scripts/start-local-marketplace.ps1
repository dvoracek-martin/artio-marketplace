$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$stateDirectory = Join-Path $repoRoot '.local-marketplace'
$envFile = Join-Path $stateDirectory '.env'
$htpasswdFile = Join-Path $stateDirectory 'zot.htpasswd'
$credentialsFile = Join-Path $stateDirectory 'credentials.txt'
$composeFile = Join-Path $repoRoot 'deployment/compose.yaml'
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

function Write-Utf8File([string]$Path, [string]$Content) {
  [System.IO.File]::WriteAllText($Path, $Content, $utf8NoBom)
}

function New-RandomHex([int]$ByteCount = 32) {
  $bytes = [byte[]]::new($ByteCount)
  $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
  return [System.BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
}

function Get-Sha256([string]$Value) {
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
  $sha = [System.Security.Cryptography.SHA256]::Create()
  try { return [System.BitConverter]::ToString($sha.ComputeHash($bytes)).Replace('-', '').ToLowerInvariant() }
  finally { $sha.Dispose() }
}

function Find-FreeLoopbackPort([int]$Start, [int[]]$Used) {
  for ($candidate = $Start; $candidate -lt ($Start + 200); $candidate++) {
    if ($Used -contains $candidate) { continue }
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $candidate)
    try {
      $listener.Start()
      return $candidate
    } catch {
      continue
    } finally {
      $listener.Stop()
    }
  }
  throw "Could not find a free loopback port starting at $Start."
}

function Set-ComposeEnvironmentFromFile([string]$Path) {
  foreach ($line in Get-Content -LiteralPath $Path) {
    if ($line -match '^([A-Z0-9_]+)=(.*)$') {
      [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
  }
}

function Test-DockerCommand([string]$Arguments) {
  & $env:ComSpec /d /c "docker $Arguments >nul 2>&1"
  return ($LASTEXITCODE -eq 0)
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
  throw 'Docker is not installed or is not on PATH. Install/start Docker Desktop and try again.'
}
if (-not (Test-DockerCommand 'info')) { throw 'Docker Engine is not running. Start Docker Desktop and run this shortcut again.' }
if (-not (Test-DockerCommand 'compose version')) { throw 'Docker Compose v2 is required.' }

New-Item -ItemType Directory -Path $stateDirectory -Force | Out-Null
if (-not (Test-Path -LiteralPath $envFile)) {
  Write-Host 'First start: creating separate local API credentials and selecting free loopback ports...'
  $publisherToken = New-RandomHex
  $adminToken = New-RandomHex
  $revocationToken = New-RandomHex
  $zotPassword = New-RandomHex
  $usedPorts = [System.Collections.Generic.List[int]]::new()
  $apiPort = Find-FreeLoopbackPort 18080 $usedPorts.ToArray(); $usedPorts.Add($apiPort)
  $managementPort = Find-FreeLoopbackPort 18081 $usedPorts.ToArray(); $usedPorts.Add($managementPort)
  $registryPort = Find-FreeLoopbackPort 15000 $usedPorts.ToArray(); $usedPorts.Add($registryPort)
  $registryPublishPort = Find-FreeLoopbackPort 15001 $usedPorts.ToArray(); $usedPorts.Add($registryPublishPort)

  $httpdImage = 'httpd:2.4-alpine'
  $httpdImageWasPresent = Test-DockerCommand "image inspect $httpdImage"
  try {
    $zotEntry = $zotPassword | docker run --rm -i --entrypoint htpasswd $httpdImage -Bbn -C 12 -i publisher
    if ($LASTEXITCODE -ne 0 -or -not $zotEntry -or $zotEntry -notmatch '^publisher:\$2[aby]\$') {
      throw 'Could not generate the Zot bcrypt password entry.'
    }
    Write-Utf8File $htpasswdFile ($zotEntry.Trim() + "`n")
  } finally {
    if (-not $httpdImageWasPresent) { Test-DockerCommand "image rm $httpdImage" | Out-Null }
  }

  Write-Utf8File (Join-Path $stateDirectory 'publisher-token.txt') ($publisherToken + "`n")
  Write-Utf8File (Join-Path $stateDirectory 'admin-token.txt') ($adminToken + "`n")
  Write-Utf8File (Join-Path $stateDirectory 'revocation-token.txt') ($revocationToken + "`n")
  Write-Utf8File (Join-Path $stateDirectory 'zot-password.txt') ($zotPassword + "`n")
  Write-Utf8File $credentialsFile @"
Publisher API bearer token: publisher-token.txt
Admin API bearer token: admin-token.txt
Release revocation API bearer token: revocation-token.txt
Zot Docker login password for user 'publisher': zot-password.txt

These are local secrets. Do not share them or commit this folder.
"@
  $envValues = @(
    "POSTGRES_PASSWORD=$(New-RandomHex)"
    "MARKETPLACE_PUBLISHER_TOKEN_SHA256=$(Get-Sha256 $publisherToken)"
    "MARKETPLACE_ADMIN_TOKEN_SHA256=$(Get-Sha256 $adminToken)"
    "MARKETPLACE_REVOCATION_TOKEN_SHA256=$(Get-Sha256 $revocationToken)"
    "MARKETPLACE_REGISTRY_HOST=localhost:$registryPort"
    "MARKETPLACE_REGISTRY_PUBLISH_HOST=localhost:$registryPublishPort"
    "MARKETPLACE_API_PORT=$apiPort"
    "MARKETPLACE_MANAGEMENT_PORT=$managementPort"
    "MARKETPLACE_REGISTRY_PORT=$registryPort"
    "MARKETPLACE_REGISTRY_PUBLISH_PORT=$registryPublishPort"
    'MARKETPLACE_REGISTRY_ALLOWED_REPOSITORY_PREFIXES=public/plugins/'
    'MARKETPLACE_RATE_LIMIT_TRUSTED_PROXIES='
    'ZOT_HTPASSWD_FILE=../.local-marketplace/zot.htpasswd'
  )
  Write-Utf8File $envFile (($envValues -join "`n") + "`n")
  Write-Utf8File (Join-Path $stateDirectory 'connection.txt') @"
Marketplace API:       http://127.0.0.1:$apiPort
Readiness:             http://127.0.0.1:$managementPort/actuator/health/readiness
Public OCI registry:   localhost:$registryPort
Zot registry UI:       http://127.0.0.1:$registryPort
Publisher OCI registry: localhost:$registryPublishPort
"@
} elseif (-not (Test-Path -LiteralPath $htpasswdFile) -or -not (Test-Path -LiteralPath $credentialsFile)) {
  throw "Local configuration exists at $stateDirectory but is incomplete. Review that folder before removing it and starting again."
}

Set-ComposeEnvironmentFromFile $envFile
$composeArgs = @('--project-name', 'artio-marketplace-local', '--env-file', $envFile, '-f', $composeFile, '-f', (Join-Path $repoRoot 'deployment/compose.dev.yaml'))
Write-Host 'Building and starting Marketplace, PostgreSQL, Zot (including its local web UI), and the registry gateway...'
docker compose @composeArgs up --build -d
if ($LASTEXITCODE -ne 0) { throw 'Docker Compose failed to start. Check Docker Desktop and the messages above.' }

$apiPort = [int]$env:MARKETPLACE_API_PORT
$managementPort = [int]$env:MARKETPLACE_MANAGEMENT_PORT
$registryPort = [int]$env:MARKETPLACE_REGISTRY_PORT
$readyUrl = "http://127.0.0.1:$managementPort/actuator/health/readiness"
$registryUrl = "http://127.0.0.1:$registryPort/v2/"
$deadline = (Get-Date).AddMinutes(3)
$ready = $false
while ((Get-Date) -lt $deadline) {
  $apiReady = $false
  $registryReady = $false
  try {
    $health = Invoke-RestMethod -Uri $readyUrl -TimeoutSec 4
    $apiReady = ($health.status -eq 'UP')
  } catch { }
  try {
    $registryResponse = Invoke-WebRequest -UseBasicParsing -Uri $registryUrl -TimeoutSec 4
    $registryReady = ([int]$registryResponse.StatusCode -eq 200)
  } catch { }
  if ($apiReady -and $registryReady) { $ready = $true; break }
  Start-Sleep -Seconds 3
}
if (-not $ready) {
  docker compose @composeArgs ps
  throw "Marketplace did not become ready within three minutes. Inspect its logs with: docker compose --project-name artio-marketplace-local --env-file .local-marketplace/.env -f deployment/compose.yaml logs"
}

Write-Host ''
Write-Host 'Marketplace is ready.' -ForegroundColor Green
Get-Content -LiteralPath (Join-Path $stateDirectory 'connection.txt')
Write-Host "Zot registry UI:      http://127.0.0.1:$registryPort"
Write-Host "Local API credentials: $stateDirectory (publisher-token.txt, admin-token.txt, revocation-token.txt)"
Write-Host 'Keep that folder private. Stop with: docker compose --project-name artio-marketplace-local --env-file .local-marketplace/.env -f deployment/compose.yaml down'
