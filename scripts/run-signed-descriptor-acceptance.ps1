param(
  [string]$CorePath = 'D:\Development\Projects\Artio',
  [string]$ProjectName = 'artio-marketplace-signed-descriptor-acceptance',
  [string]$PythonPath = '',
  [string]$CleanupTemporaryDirectory = '',
  [int]$ApiPort = 18080,
  [int]$ManagementPort = 18081,
  [int]$RegistryPort = 15100,
  [int]$RegistryPublishPort = 15101
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$taskTemp = Join-Path $env:TEMP ('artio-marketplace-signed-descriptor-' + [guid]::NewGuid().ToString('N'))
$envFile = Join-Path $taskTemp '.env'
$openssl = 'C:\Program Files\Git\usr\bin\openssl.exe'
$gitBash = 'C:\Program Files\Git\bin\bash.exe'
$stackStarted = $false
$marketplaceImageExisted = $false
$htpasswdImageExisted = $false
$testBaseImages = @(
  'nginx:1.29-alpine@sha256:5616878291a2eed594aee8db4dade5878cf7edcb475e59193904b198d9b830de',
  'postgres@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea',
  'eclipse-temurin:25-jdk-noble@sha256:589ff4cc3f71aab462e7048a47a0d10edf57fbccde3fceea2281e610bf5880b4',
  'eclipse-temurin:25-jre-noble@sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab',
  'ghcr.io/project-zot/zot@sha256:96cda11459ce6f8c60da3b03f03e9f67fd11b3ae2d82c4c04c92b3c608996080'
)
$testBaseImagesAbsentBefore = [System.Collections.Generic.List[string]]::new()

function New-Token {
  $bytes = [byte[]]::new(32)
  [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
  [Convert]::ToHexString($bytes).ToLowerInvariant()
}

function Get-Sha256([string]$value) {
  [Convert]::ToHexString([System.Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($value))).ToLowerInvariant()
}

function Convert-ToBashPath([string]$path) {
  $drive = $path.Substring(0, 1).ToLowerInvariant()
  '/' + $drive + '/' + $path.Substring(3).Replace('\', '/')
}

if (-not (Test-Path -LiteralPath $CorePath)) { throw "Core checkout was not found: $CorePath" }
if (-not (Test-Path -LiteralPath $openssl)) { throw 'Git for Windows OpenSSL was not found.' }
if (-not (Test-Path -LiteralPath $gitBash)) { throw 'Git Bash was not found.' }
if ($ProjectName -notmatch '^[a-z0-9][a-z0-9_-]{2,50}$') { throw 'ProjectName must be a unique Docker Compose project name.' }
if (@($ApiPort, $ManagementPort, $RegistryPort, $RegistryPublishPort | Sort-Object -Unique).Count -ne 4 -or
    @($ApiPort, $ManagementPort, $RegistryPort, $RegistryPublishPort | Where-Object { $_ -lt 1024 -or $_ -gt 65535 }).Count -gt 0) {
  throw 'Acceptance host ports must be four distinct TCP ports between 1024 and 65535.'
}
if ($CleanupTemporaryDirectory) {
  $tempRoot = [IO.Path]::GetFullPath($env:TEMP).TrimEnd('\') + '\'
  $cleanupTarget = [IO.Path]::GetFullPath($CleanupTemporaryDirectory)
  if (-not $cleanupTarget.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
      -not (Split-Path -Leaf $cleanupTarget).StartsWith('artio-marketplace-signed-descriptor-', [StringComparison]::Ordinal)) {
    throw 'Cleanup target must be a signed-descriptor acceptance directory directly under the system temporary directory.'
  }
  if (Test-Path -LiteralPath $cleanupTarget) {
    Remove-Item -LiteralPath $cleanupTarget -Recurse -Force
  }
  Write-Output 'Removed only the requested signed-descriptor acceptance temporary directory.'
  return
}
$existingProjectResources = @(
  docker ps -a --filter "label=com.docker.compose.project=$ProjectName" --format '{{.ID}}'
  docker volume ls --filter "label=com.docker.compose.project=$ProjectName" --format '{{.Name}}'
)
if ($existingProjectResources.Count -gt 0) { throw "Refusing to reuse Docker resources already owned by project '$ProjectName'. Choose a fresh project name." }
New-Item -ItemType Directory -Path $taskTemp | Out-Null

try {
  docker image inspect artio-marketplace:local *> $null
  $marketplaceImageExisted = ($LASTEXITCODE -eq 0)
  docker image inspect httpd:2.4-alpine *> $null
  $htpasswdImageExisted = ($LASTEXITCODE -eq 0)
  foreach ($image in $testBaseImages) {
    docker image inspect $image *> $null
    if ($LASTEXITCODE -ne 0) { $testBaseImagesAbsentBefore.Add($image) }
  }

  $publisher = New-Token
  $admin = New-Token
  $revocation = New-Token
  $signing = New-Token
  $zotPassword = New-Token
  $promoterPassword = New-Token
  $postgresPassword = New-Token
  $keyId = 'local-acceptance-2026'
  $keyPath = Join-Path $taskTemp 'release-signing-key.pem'
  $publicKeyPath = Join-Path $taskTemp 'release-signing-public.der'
  $advisoryKeyId = 'local-acceptance-advisory-2026'
  $advisoryKeyPath = Join-Path $taskTemp 'advisory-signing-key.pem'
  $advisoryPrivateKeyPath = Join-Path $taskTemp 'advisory-signing-private.der'
  $advisoryPublicKeyPath = Join-Path $taskTemp 'advisory-signing-public.der'
  & $openssl genpkey -algorithm Ed25519 -out $keyPath
  if ($LASTEXITCODE -ne 0) { throw 'Could not generate isolated signing key.' }
  & $openssl pkey -in $keyPath -pubout -outform DER -out $publicKeyPath
  if ($LASTEXITCODE -ne 0) { throw 'Could not derive isolated signing key.' }
  $publicKey = [Convert]::ToBase64String([IO.File]::ReadAllBytes($publicKeyPath)).TrimEnd('=').Replace('+', '-').Replace('/', '_')
  & $openssl genpkey -algorithm Ed25519 -out $advisoryKeyPath
  if ($LASTEXITCODE -ne 0) { throw 'Could not generate isolated advisory signing key.' }
  & $openssl pkey -in $advisoryKeyPath -outform DER -out $advisoryPrivateKeyPath
  if ($LASTEXITCODE -ne 0) { throw 'Could not encode isolated advisory private key.' }
  & $openssl pkey -in $advisoryKeyPath -pubout -outform DER -out $advisoryPublicKeyPath
  if ($LASTEXITCODE -ne 0) { throw 'Could not derive isolated advisory signing key.' }
  $advisoryPrivateKey = [Convert]::ToBase64String([IO.File]::ReadAllBytes($advisoryPrivateKeyPath))
  $advisoryPublicKey = [Convert]::ToBase64String([IO.File]::ReadAllBytes($advisoryPublicKeyPath)).TrimEnd('=').Replace('+', '-').Replace('/', '_')

  $publisherLine = (& docker run --rm --entrypoint htpasswd httpd:2.4-alpine -Bbn publisher $zotPassword)
  if ($LASTEXITCODE -ne 0) { throw 'Could not create isolated Zot publisher credential.' }
  $promoterLine = (& docker run --rm --entrypoint htpasswd httpd:2.4-alpine -Bbn promoter $promoterPassword)
  if ($LASTEXITCODE -ne 0) { throw 'Could not create isolated Zot promotion credential.' }
  $htpasswdPath = Join-Path $taskTemp 'zot.htpasswd'
  [IO.File]::WriteAllLines($htpasswdPath, @($publisherLine, $promoterLine), [Text.UTF8Encoding]::new($false))

  $values = @(
    "POSTGRES_PASSWORD=$postgresPassword",
    "MARKETPLACE_PUBLISHER_TOKEN_SHA256=$(Get-Sha256 $publisher)",
    "MARKETPLACE_ADMIN_TOKEN_SHA256=$(Get-Sha256 $admin)",
    "MARKETPLACE_REVOCATION_TOKEN_SHA256=$(Get-Sha256 $revocation)",
    "MARKETPLACE_SIGNING_INGEST_TOKEN_SHA256=$(Get-Sha256 $signing)",
    "MARKETPLACE_SIGNING_TRUSTED_KEYS=$keyId=$publicKey",
    "MARKETPLACE_ADVISORY_SIGNING_KEY_ID=$advisoryKeyId",
    "MARKETPLACE_ADVISORY_SIGNING_PRIVATE_KEY_BASE64=$advisoryPrivateKey",
    'MARKETPLACE_ZOT_PROMOTION_USERNAME=promoter',
    "MARKETPLACE_ZOT_PROMOTION_PASSWORD=$promoterPassword",
    'MARKETPLACE_PUBLISHER_RATE_LIMIT_PER_MINUTE=200',
    "MARKETPLACE_REGISTRY_HOST=localhost:$RegistryPort",
    "MARKETPLACE_API_PORT=$ApiPort",
    "MARKETPLACE_MANAGEMENT_PORT=$ManagementPort",
    "MARKETPLACE_REGISTRY_PORT=$RegistryPort",
    "MARKETPLACE_REGISTRY_PUBLISH_PORT=$RegistryPublishPort",
    "ZOT_HTPASSWD_FILE=$($htpasswdPath.Replace('\', '/'))"
  )
  [IO.File]::WriteAllLines($envFile, $values, [Text.UTF8Encoding]::new($false))

  $tokenFiles = @{
    'publisher-token.txt' = $publisher
    'admin-token.txt' = $admin
    'revocation-token.txt' = $revocation
    'signing-ingest-token.txt' = $signing
  }
  foreach ($name in $tokenFiles.Keys) {
    [IO.File]::WriteAllText((Join-Path $taskTemp $name), $tokenFiles[$name], [Text.UTF8Encoding]::new($false))
  }

  $composeEnv = Convert-ToBashPath $envFile
  $bashTemp = Convert-ToBashPath $taskTemp
  $env:MARKETPLACE_COMPOSE_PROJECT = $ProjectName
  $env:MARKETPLACE_ENV_FILE = $composeEnv
  $env:MARKETPLACE_URL = "http://127.0.0.1:$ApiPort"
  $env:MARKETPLACE_MANAGEMENT_URL = "http://127.0.0.1:$ManagementPort"
  $env:MARKETPLACE_PUBLISHER_TOKEN_FILE = "$bashTemp/publisher-token.txt"
  $env:MARKETPLACE_ADMIN_TOKEN_FILE = "$bashTemp/admin-token.txt"
  $env:MARKETPLACE_REVOCATION_TOKEN_FILE = "$bashTemp/revocation-token.txt"
  $env:MARKETPLACE_SIGNING_INGEST_TOKEN_FILE = "$bashTemp/signing-ingest-token.txt"
  $env:MARKETPLACE_SIGNING_PRIVATE_KEY_FILE = "$bashTemp/release-signing-key.pem"
  $env:MARKETPLACE_SIGNING_KEY_ID = $keyId
  $env:ARTIO_MARKETPLACE_ADVISORY_TRUSTED_KEYS = "$advisoryKeyId=$advisoryPublicKey"
  $env:MARKETPLACE_REGISTRY_HOST = "localhost:$RegistryPort"
  $env:MARKETPLACE_REGISTRY_CONNECT_HOST = "localhost:$RegistryPort"
  $env:MARKETPLACE_REGISTRY_PUBLISH_HOST = "localhost:$RegistryPublishPort"
  $env:MARKETPLACE_REGISTRY_PUBLISH_CONNECT_HOST = "localhost:$RegistryPublishPort"
  if ($PythonPath) {
    if (-not (Test-Path -LiteralPath $PythonPath)) { throw "Python executable was not found: $PythonPath" }
    $env:MARKETPLACE_PYTHON = Convert-ToBashPath (Resolve-Path -LiteralPath $PythonPath).Path
  }
  $env:MARKETPLACE_ZOT_TEST_PASSWORD = $zotPassword
  $env:MARKETPLACE_ZOT_PROMOTION_TEST_PASSWORD = $promoterPassword
  $env:ARTIO_CORE_PATH = (Resolve-Path $CorePath).Path

  Push-Location $repo
  try {
    $stackStarted = $true
    docker compose --project-name $ProjectName --env-file $envFile -f deployment/compose.yaml up --build -d
    if ($LASTEXITCODE -ne 0) { throw 'Could not start the isolated Marketplace acceptance stack.' }
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
      try {
        $response = Invoke-WebRequest -Uri "http://127.0.0.1:$ManagementPort/actuator/health/readiness" -TimeoutSec 3
        if ($response.StatusCode -eq 200) { $ready = $true; break }
      } catch { Start-Sleep -Seconds 3 }
    }
    if (-not $ready) {
      docker compose --project-name $ProjectName --env-file $envFile -f deployment/compose.yaml logs --no-color
      throw 'Marketplace did not become ready.'
    }
    & $gitBash -c './scripts/docker-acceptance.sh'
    if ($LASTEXITCODE -ne 0) { throw "Integrated acceptance failed with exit code $LASTEXITCODE." }
    Write-Output 'Signed Zot to Marketplace to Core acceptance passed.'
  } finally {
    Pop-Location
  }
} finally {
  if ($stackStarted) {
    docker compose --project-name $ProjectName --env-file $envFile -f (Join-Path $repo 'deployment/compose.yaml') down --volumes --remove-orphans
  }
  if (-not $marketplaceImageExisted) { docker image rm artio-marketplace:local 2>$null | Out-Null }
  if (-not $htpasswdImageExisted) { docker image rm httpd:2.4-alpine 2>$null | Out-Null }
  foreach ($image in $testBaseImagesAbsentBefore) { docker image rm $image 2>$null | Out-Null }
  $tempRoot = [IO.Path]::GetFullPath($env:TEMP).TrimEnd('\') + '\'
  $target = [IO.Path]::GetFullPath($taskTemp)
  if ($target.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -and (Split-Path -Leaf $target).StartsWith('artio-marketplace-signed-descriptor-', [StringComparison]::Ordinal)) {
    Remove-Item -LiteralPath $target -Recurse -Force
  }
}
