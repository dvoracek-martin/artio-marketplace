$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$stateDirectory = Join-Path $repoRoot '.local-marketplace'
$envFile = Join-Path $stateDirectory '.env'
$htpasswdFile = Join-Path $stateDirectory 'zot.htpasswd'
$credentialsFile = Join-Path $stateDirectory 'credentials.txt'
$advisoryTrustedKeyFile = Join-Path $stateDirectory 'advisory-trusted-key.txt'
$releaseTrustedKeyFile = Join-Path $stateDirectory 'release-trusted-key.txt'
$releaseSigningKeyFile = Join-Path $stateDirectory 'release-signing-key.pem'
$importerPasswordFile = Join-Path $stateDirectory 'zot-importer-password.txt'
$signingIngestTokenFile = Join-Path $stateDirectory 'signing-ingest-token.txt'
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

function Ensure-LocalAdvisorySigningKey {
  $keyIdEntry = Get-Content -LiteralPath $envFile | Where-Object { $_ -match '^MARKETPLACE_ADVISORY_SIGNING_KEY_ID=.+$' } | Select-Object -First 1
  $privateKeyEntry = Get-Content -LiteralPath $envFile | Where-Object { $_ -match '^MARKETPLACE_ADVISORY_SIGNING_PRIVATE_KEY_BASE64=.+$' } | Select-Object -First 1
  if ($keyIdEntry -and $privateKeyEntry -and (Test-Path -LiteralPath $advisoryTrustedKeyFile)) { return }

  $openssl = $env:OPENSSL_BIN
  if (-not $openssl -or -not (Test-Path -LiteralPath $openssl)) {
    $opensslCommand = Get-Command openssl -ErrorAction SilentlyContinue
    if ($opensslCommand) { $openssl = $opensslCommand.Source }
    elseif (Test-Path -LiteralPath 'C:\Program Files\Git\usr\bin\openssl.exe') { $openssl = 'C:\Program Files\Git\usr\bin\openssl.exe' }
  }
  if (-not $openssl -or -not (Test-Path -LiteralPath $openssl)) {
    throw 'OpenSSL is required to create the local Marketplace advisory signing key. Install Git for Windows or set OPENSSL_BIN.'
  }

  $keyTemp = Join-Path ([IO.Path]::GetTempPath()) ('artio-local-advisory-' + [guid]::NewGuid().ToString('N'))
  New-Item -ItemType Directory -Path $keyTemp | Out-Null
  try {
    $privatePem = Join-Path $keyTemp 'private.pem'
    $privateDer = Join-Path $keyTemp 'private.der'
    $publicDer = Join-Path $keyTemp 'public.der'
    & $openssl genpkey -algorithm Ed25519 -out $privatePem
    if ($LASTEXITCODE -ne 0) { throw 'Could not generate the local Marketplace advisory signing key.' }
    & $openssl pkey -in $privatePem -outform DER -out $privateDer
    if ($LASTEXITCODE -ne 0) { throw 'Could not encode the local Marketplace advisory signing key.' }
    & $openssl pkey -in $privatePem -pubout -outform DER -out $publicDer
    if ($LASTEXITCODE -ne 0) { throw 'Could not derive the local Marketplace advisory verification key.' }

    $keyId = 'local-dev-' + (New-RandomHex 6)
    $privateBase64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($privateDer))
    $publicBase64Url = [Convert]::ToBase64String([IO.File]::ReadAllBytes($publicDer)).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    $environment = @(Get-Content -LiteralPath $envFile | Where-Object { $_ -notmatch '^MARKETPLACE_ADVISORY_SIGNING_(KEY_ID|PRIVATE_KEY_BASE64)=' })
    $environment += "MARKETPLACE_ADVISORY_SIGNING_KEY_ID=$keyId"
    $environment += "MARKETPLACE_ADVISORY_SIGNING_PRIVATE_KEY_BASE64=$privateBase64"
    Write-Utf8File $envFile (($environment -join "`n") + "`n")
    Write-Utf8File $advisoryTrustedKeyFile ("$keyId=$publicBase64Url`n")
  } finally {
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    $target = [IO.Path]::GetFullPath($keyTemp)
    if ($target.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -and
      (Split-Path -Leaf $target).StartsWith('artio-local-advisory-', [StringComparison]::Ordinal)) {
      Remove-Item -LiteralPath $target -Recurse -Force
    }
  }
}

function Protect-LocalSecretFile([string]$Path) {
  $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
  $acl = [System.Security.AccessControl.FileSecurity]::new()
  $acl.SetOwner([System.Security.Principal.NTAccount]::new($identity))
  $acl.SetAccessRuleProtection($true, $false)
  $rule = [System.Security.AccessControl.FileSystemAccessRule]::new(
    $identity, 'FullControl', 'Allow')
  $acl.AddAccessRule($rule)
  Set-Acl -LiteralPath $Path -AclObject $acl
}

function Ensure-LocalReleaseSigningKey {
  $environment = @(Get-Content -LiteralPath $envFile)
  $keyIdEntry = $environment | Where-Object { $_ -match '^MARKETPLACE_SIGNING_KEY_ID=.+$' } | Select-Object -First 1
  $trustedEntry = $environment | Where-Object { $_ -match '^MARKETPLACE_SIGNING_TRUSTED_KEYS=.+$' } | Select-Object -First 1
  $ingestEntry = $environment | Where-Object { $_ -match '^MARKETPLACE_SIGNING_INGEST_TOKEN_SHA256=.+$' } | Select-Object -First 1
  $existing = @($keyIdEntry, $trustedEntry, $ingestEntry) | Where-Object { $_ }
  if ($existing.Count -gt 0) {
    if ($existing.Count -ne 3 -or -not (Test-Path -LiteralPath $releaseTrustedKeyFile) -or
      -not (Test-Path -LiteralPath $releaseSigningKeyFile) -or -not (Test-Path -LiteralPath $signingIngestTokenFile)) {
      throw 'Local Marketplace release-signing state is incomplete. Refusing to replace a possibly trusted development key.'
    }
    return
  }

  $openssl = $env:OPENSSL_BIN
  if (-not $openssl -or -not (Test-Path -LiteralPath $openssl)) {
    $opensslCommand = Get-Command openssl -ErrorAction SilentlyContinue
    if ($opensslCommand) { $openssl = $opensslCommand.Source }
    elseif (Test-Path -LiteralPath 'C:\Program Files\Git\usr\bin\openssl.exe') { $openssl = 'C:\Program Files\Git\usr\bin\openssl.exe' }
  }
  if (-not $openssl -or -not (Test-Path -LiteralPath $openssl)) {
    throw 'OpenSSL is required to create the local Marketplace release-signing key. Install Git for Windows or set OPENSSL_BIN.'
  }

  $keyTemp = Join-Path ([IO.Path]::GetTempPath()) ('artio-local-release-' + [guid]::NewGuid().ToString('N'))
  New-Item -ItemType Directory -Path $keyTemp | Out-Null
  try {
    $privatePem = Join-Path $keyTemp 'private.pem'
    $publicDer = Join-Path $keyTemp 'public.der'
    & $openssl genpkey -algorithm Ed25519 -out $privatePem
    if ($LASTEXITCODE -ne 0) { throw 'Could not generate the local Marketplace release-signing key.' }
    & $openssl pkey -in $privatePem -pubout -outform DER -out $publicDer
    if ($LASTEXITCODE -ne 0) { throw 'Could not derive the local Marketplace release verification key.' }

    $keyId = 'local-dev-release-' + (New-RandomHex 6)
    $publicBase64Url = [Convert]::ToBase64String([IO.File]::ReadAllBytes($publicDer)).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    $signingToken = New-RandomHex
    Copy-Item -LiteralPath $privatePem -Destination $releaseSigningKeyFile
    [IO.File]::WriteAllText($signingIngestTokenFile, $signingToken + "`n", $utf8NoBom)
    Protect-LocalSecretFile $releaseSigningKeyFile
    Protect-LocalSecretFile $signingIngestTokenFile
    Write-Utf8File $releaseTrustedKeyFile ("$keyId=$publicBase64Url`n")

    $environment = @(Get-Content -LiteralPath $envFile)
    $environment += "MARKETPLACE_SIGNING_KEY_ID=$keyId"
    $environment += "MARKETPLACE_SIGNING_TRUSTED_KEYS=$keyId=$publicBase64Url"
    $environment += "MARKETPLACE_SIGNING_INGEST_TOKEN_SHA256=$(Get-Sha256 $signingToken)"
    Write-Utf8File $envFile (($environment -join "`n") + "`n")
  } finally {
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    $target = [IO.Path]::GetFullPath($keyTemp)
    if ($target.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -and
      (Split-Path -Leaf $target).StartsWith('artio-local-release-', [StringComparison]::Ordinal)) {
      Remove-Item -LiteralPath $target -Recurse -Force
    }
  }
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
  $promotionPassword = New-RandomHex
  $usedPorts = [System.Collections.Generic.List[int]]::new()
  $apiPort = Find-FreeLoopbackPort 18080 $usedPorts.ToArray(); $usedPorts.Add($apiPort)
  $managementPort = Find-FreeLoopbackPort 18081 $usedPorts.ToArray(); $usedPorts.Add($managementPort)
  $registryPort = Find-FreeLoopbackPort 15000 $usedPorts.ToArray(); $usedPorts.Add($registryPort)
  $registryPublishPort = Find-FreeLoopbackPort 15001 $usedPorts.ToArray(); $usedPorts.Add($registryPublishPort)

  $httpdImage = 'httpd:2.4-alpine'
  $httpdImageWasPresent = Test-DockerCommand "image inspect $httpdImage"
  try {
    $zotPublisherEntry = $zotPassword | docker run --rm -i --entrypoint htpasswd $httpdImage -Bbn -C 12 -i publisher
    $zotPromoterEntry = $promotionPassword | docker run --rm -i --entrypoint htpasswd $httpdImage -Bbn -C 12 -i promoter
    if ($LASTEXITCODE -ne 0 -or -not $zotPublisherEntry -or $zotPublisherEntry -notmatch '^publisher:\$2[aby]\$' -or
      -not $zotPromoterEntry -or $zotPromoterEntry -notmatch '^promoter:\$2[aby]\$') {
      throw 'Could not generate the Zot bcrypt password entry.'
    }
    Write-Utf8File $htpasswdFile ($zotPublisherEntry.Trim() + "`n" + $zotPromoterEntry.Trim() + "`n")
  } finally {
    if (-not $httpdImageWasPresent) { Test-DockerCommand "image rm $httpdImage" | Out-Null }
  }

  Write-Utf8File (Join-Path $stateDirectory 'publisher-token.txt') ($publisherToken + "`n")
  Write-Utf8File (Join-Path $stateDirectory 'admin-token.txt') ($adminToken + "`n")
  Write-Utf8File (Join-Path $stateDirectory 'revocation-token.txt') ($revocationToken + "`n")
  Write-Utf8File (Join-Path $stateDirectory 'zot-password.txt') ($zotPassword + "`n")
  Write-Utf8File (Join-Path $stateDirectory 'zot-promotion-password.txt') ($promotionPassword + "`n")
  Write-Utf8File $credentialsFile @"
Publisher API bearer token: publisher-token.txt
Admin API bearer token: admin-token.txt
Release revocation API bearer token: revocation-token.txt
Zot Docker login password for user 'publisher': zot-password.txt
Private OCI promotion credential is stored separately in zot-promotion-password.txt.

These are local secrets. Do not share them or commit this folder.
"@
  $envValues = @(
    "POSTGRES_PASSWORD=$(New-RandomHex)"
    "MARKETPLACE_PUBLISHER_TOKEN_SHA256=$(Get-Sha256 $publisherToken)"
    "MARKETPLACE_ADMIN_TOKEN_SHA256=$(Get-Sha256 $adminToken)"
    "MARKETPLACE_REVOCATION_TOKEN_SHA256=$(Get-Sha256 $revocationToken)"
    'MARKETPLACE_ZOT_PROMOTION_USERNAME=promoter'
    "MARKETPLACE_ZOT_PROMOTION_PASSWORD=$promotionPassword"
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
Marketplace admin:     http://127.0.0.1:$apiPort/dev-admin/
Readiness:             http://127.0.0.1:$managementPort/actuator/health/readiness
Public OCI registry:   localhost:$registryPort
Zot registry UI:       http://127.0.0.1:$registryPort
Publisher OCI registry: localhost:$registryPublishPort
"@
} elseif (-not (Test-Path -LiteralPath $htpasswdFile) -or -not (Test-Path -LiteralPath $credentialsFile)) {
  throw "Local configuration exists at $stateDirectory but is incomplete. Review that folder before removing it and starting again."
}

Ensure-LocalAdvisorySigningKey
Ensure-LocalReleaseSigningKey

$promotionPasswordFile = Join-Path $stateDirectory 'zot-promotion-password.txt'
$hasPromotionPassword = Select-String -LiteralPath $envFile -Pattern '^MARKETPLACE_ZOT_PROMOTION_PASSWORD=.+$' -Quiet
$hasPromoter = Select-String -LiteralPath $htpasswdFile -Pattern '^promoter:\$2[aby]\$' -Quiet
if (-not $hasPromotionPassword -or -not $hasPromoter) {
  $promotionPassword = New-RandomHex
  $httpdImage = 'httpd:2.4-alpine'
  $httpdImageWasPresent = Test-DockerCommand "image inspect $httpdImage"
  try {
    $zotPromoterEntry = $promotionPassword | docker run --rm -i --entrypoint htpasswd $httpdImage -Bbn -C 12 -i promoter
    if ($LASTEXITCODE -ne 0 -or -not $zotPromoterEntry -or $zotPromoterEntry -notmatch '^promoter:\$2[aby]\$') {
      throw 'Could not generate the Zot promotion credential.'
    }
    $htpasswdEntries = @(Get-Content -LiteralPath $htpasswdFile | Where-Object { $_ -notmatch '^promoter:' })
    $htpasswdEntries += $zotPromoterEntry.Trim()
    Write-Utf8File $htpasswdFile (($htpasswdEntries -join "`n") + "`n")
  } finally {
    if (-not $httpdImageWasPresent) { Test-DockerCommand "image rm $httpdImage" | Out-Null }
  }
  $envContents = @(Get-Content -LiteralPath $envFile | Where-Object { $_ -notmatch '^MARKETPLACE_ZOT_PROMOTION_(USERNAME|PASSWORD)=' })
  $envContents += 'MARKETPLACE_ZOT_PROMOTION_USERNAME=promoter'
  $envContents += "MARKETPLACE_ZOT_PROMOTION_PASSWORD=$promotionPassword"
  Write-Utf8File $envFile (($envContents -join "`n") + "`n")
  Write-Utf8File $promotionPasswordFile ($promotionPassword + "`n")
  $credentialText = Get-Content -Raw -LiteralPath $credentialsFile
  if ($credentialText -notmatch 'zot-promotion-password\.txt') {
    Write-Utf8File $credentialsFile ($credentialText.TrimEnd() + "`nPrivate OCI promotion credential: zot-promotion-password.txt`n")
  }
}

# Keep the archive importer separate from publisher and promoter credentials; Zot grants it
# staging-only access in the development configuration. Never rewrite an existing password.
$hasImporterPassword = Test-Path -LiteralPath $importerPasswordFile
$hasImporterUser = Select-String -LiteralPath $htpasswdFile -Pattern '^oci-importer:\$2[aby]\$' -Quiet
if (-not $hasImporterPassword -or -not $hasImporterUser) {
  if ($hasImporterPassword -xor $hasImporterUser) {
    throw 'The local Zot importer credential is incomplete. Review .local-marketplace before continuing; no existing secret was rotated.'
  }
  $importerPassword = New-RandomHex
  $httpdImage = 'httpd:2.4-alpine'
  $httpdImageWasPresent = Test-DockerCommand "image inspect $httpdImage"
  try {
    $zotImporterEntry = $importerPassword | docker run --rm -i --entrypoint htpasswd $httpdImage -Bbn -C 12 -i oci-importer
    if ($LASTEXITCODE -ne 0 -or -not $zotImporterEntry -or $zotImporterEntry -notmatch '^oci-importer:\$2[aby]\$') {
      throw 'Could not generate the Zot staging-only importer credential.'
    }
    $htpasswdEntries = @(Get-Content -LiteralPath $htpasswdFile | Where-Object { $_ -notmatch '^oci-importer:' })
    $htpasswdEntries += $zotImporterEntry.Trim()
    Write-Utf8File $htpasswdFile (($htpasswdEntries -join "`n") + "`n")
    Write-Utf8File $importerPasswordFile ($importerPassword + "`n")
    Protect-LocalSecretFile $importerPasswordFile
    $credentialText = Get-Content -Raw -LiteralPath $credentialsFile
    if ($credentialText -notmatch 'zot-importer-password\.txt') {
      Write-Utf8File $credentialsFile ($credentialText.TrimEnd() + "`nZot staging-only OCI importer password: zot-importer-password.txt`n")
    }
  } finally {
    if (-not $httpdImageWasPresent) { Test-DockerCommand "image rm $httpdImage" | Out-Null }
  }
  $envContents = @(Get-Content -LiteralPath $envFile | Where-Object { $_ -notmatch '^MARKETPLACE_LOCAL_(SIGNING_KEY_FILE|IMPORTER_PASSWORD_FILE)=' })
  $envContents += 'MARKETPLACE_LOCAL_SIGNING_KEY_FILE=../.local-marketplace/release-signing-key.pem'
  $envContents += 'MARKETPLACE_LOCAL_IMPORTER_PASSWORD_FILE=../.local-marketplace/zot-importer-password.txt'
  Write-Utf8File $envFile (($envContents -join "`n") + "`n")
}
if (-not (Select-String -LiteralPath $envFile -Pattern '^MARKETPLACE_LOCAL_SIGNING_KEY_FILE=.+$' -Quiet)) {
  Add-Content -LiteralPath $envFile -Value 'MARKETPLACE_LOCAL_SIGNING_KEY_FILE=../.local-marketplace/release-signing-key.pem' -Encoding utf8
}
if (-not (Select-String -LiteralPath $envFile -Pattern '^MARKETPLACE_LOCAL_IMPORTER_PASSWORD_FILE=.+$' -Quiet)) {
  Add-Content -LiteralPath $envFile -Value 'MARKETPLACE_LOCAL_IMPORTER_PASSWORD_FILE=../.local-marketplace/zot-importer-password.txt' -Encoding utf8
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
Write-Host "Core advisory trust key: $advisoryTrustedKeyFile"
Write-Host "Core release trust key: $releaseTrustedKeyFile"
Write-Host "Admin GUI:             http://127.0.0.1:$apiPort/dev-admin/"
Write-Host "Zot registry UI:      http://127.0.0.1:$registryPort"
Write-Host "Local API credentials: $stateDirectory (publisher-token.txt, admin-token.txt, revocation-token.txt)"
Write-Host 'Keep that folder private. Stop with: docker compose --project-name artio-marketplace-local --env-file .local-marketplace/.env -f deployment/compose.yaml down'

if ($env:MARKETPLACE_DEV_SEED_CORE_MINIO -eq 'true') {
  $corePath = Join-Path $repoRoot '..\Artio'
  if (-not (Test-Path -LiteralPath (Join-Path $corePath 'artio-plugins/minio-object-storage'))) {
    throw 'MARKETPLACE_DEV_SEED_CORE_MINIO=true requires the sibling Artio plugin worktree.'
  }
  $env:MARKETPLACE_DEV_SEED_PROFILE = 'local-development'
  & (Join-Path $repoRoot 'scripts/seed-dev-minio-release.ps1') -CorePath $corePath
  if ($LASTEXITCODE -ne 0) { throw 'The opt-in local MinIO development seed failed.' }
} else {
  Write-Host 'No Core checkout or running Core is required. To seed the optional MinIO sample release, set MARKETPLACE_DEV_SEED_CORE_MINIO=true.'
}
