[CmdletBinding()]
param([string]$CorePath = (Join-Path $PSScriptRoot '..\..\Artio'))

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$marketplaceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$stateDirectory = Join-Path $marketplaceRoot '.local-marketplace'
$packageDirectory = Join-Path $stateDirectory 'minio-dev-package'
$releaseEvidencePath = Join-Path $stateDirectory 'minio-dev-release.json'
$pluginRoot = Join-Path (Resolve-Path $CorePath).Path 'artio-plugins\minio-object-storage'
$version = '1.1.0'
$pluginId = 'storage.artio-minio'

function Read-LocalSetting([string]$Name) {
  $line = Get-Content -LiteralPath (Join-Path $stateDirectory '.env') |
    Where-Object { $_ -match ('^' + [regex]::Escape($Name) + '=') } | Select-Object -Last 1
  if (-not $line) { throw "Local Marketplace setting $Name is missing." }
  return ($line -split '=', 2)[1]
}

function Invoke-Docker([string[]]$Arguments, [string]$Operation) {
  & docker @Arguments
  if ($LASTEXITCODE -ne 0) { throw "Docker $Operation failed (exit $LASTEXITCODE)." }
}

function Invoke-JsonApi([string]$Method, [string]$Uri, [hashtable]$Headers, [string]$Json) {
  $body = [System.Text.Encoding]::UTF8.GetBytes($Json)
  return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $Headers -ContentType 'application/json; charset=utf-8' -Body $body
}

function Get-Sha256File([string]$Path) {
  $sha = [System.Security.Cryptography.SHA256]::Create()
  try {
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
  } finally {
    $sha.Dispose()
  }
}

function Push-Image([string]$Tag) {
  $output = @(& docker push $Tag 2>&1)
  $exit = $LASTEXITCODE
  $output | ForEach-Object { Write-Host $_ }
  if ($exit -ne 0) { throw "Could not push staged development image $Tag." }
  $joined = $output -join "`n"
  $match = [regex]::Matches($joined, '(?im)digest:\s*(sha256:[a-f0-9]{64})') | Select-Object -Last 1
  if (-not $match) { throw "Registry push for $Tag did not return an OCI digest." }
  return $match.Groups[1].Value
}

function Upload-Icon([string]$ReleaseId, [string]$Token, [string]$MarketplaceUrl, [string]$IconPath) {
  $client = [System.Net.Http.HttpClient]::new()
  $client.DefaultRequestHeaders.Authorization =
    [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token)
  $form = [System.Net.Http.MultipartFormDataContent]::new()
  $stream = [System.IO.File]::OpenRead($IconPath)
  $part = [System.Net.Http.StreamContent]::new($stream)
  $part.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new('image/png')
  $form.Add($part, 'file', 'marketplace-icon.png')
  try {
    $response = $client.PostAsync("$MarketplaceUrl/api/v1/admin/marketplace/releases/$ReleaseId/assets?kind=icon", $form).GetAwaiter().GetResult()
    $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if (-not $response.IsSuccessStatusCode) { throw "Marketplace icon upload failed (HTTP $([int]$response.StatusCode)): $body" }
    return ($body | ConvertFrom-Json).assetId
  } finally {
    $form.Dispose()
    $stream.Dispose()
    $client.Dispose()
  }
}

function New-Catalog([string]$IconId) {
  return [ordered]@{
    schemaVersion = 1
    pluginId = $pluginId
    slug = 'artio-minio-object-storage'
    title = [ordered]@{ en = 'Artio MinIO Object Storage (Development)'; de = 'Artio MinIO-Objektspeicher (Entwicklung)'; it = 'Archiviazione oggetti Artio MinIO (sviluppo)'; fr = 'Stockage objet Artio MinIO (développement)' }
    tagline = [ordered]@{ en = 'Managed or external S3 storage for isolated development'; de = 'Verwalteter oder externer S3-Speicher für isolierte Entwicklung'; it = 'Archiviazione S3 gestita o esterna per lo sviluppo isolato'; fr = 'Stockage S3 géré ou externe pour le développement isolé' }
    description = [ordered]@{
      en = 'Development-only release. Includes archived MinIO Community source with known high-severity authentication bypass advisories. Use only on an isolated developer machine with disposable data. Never use for production or customer data. Supports managed local storage and administrator-configured external HTTPS S3-compatible storage.'
      de = 'Nur für die Entwicklung. Enthält archivierten MinIO-Community-Quellcode mit bekannten schwerwiegenden Authentifizierungsumgehungen. Nur auf einem isolierten Entwicklungsrechner mit entbehrlichen Daten verwenden. Niemals für Produktion oder Kundendaten verwenden.'
      it = 'Solo per lo sviluppo. Include il codice sorgente archiviato di MinIO Community con vulnerabilità note di autenticazione ad alta gravità. Usare solo su una macchina di sviluppo isolata con dati eliminabili. Non usare mai in produzione o con dati dei clienti.'
      fr = 'Réservé au développement. Inclut le code source archivé de MinIO Community avec des contournements d authentification connus et graves. À utiliser uniquement sur une machine isolée avec des données jetables. Ne jamais utiliser en production ni avec des données client.'
    }
    vendorDisplayName = 'Artio Development'
    author = 'Artio Development'
    licenseName = 'AGPL-3.0 (MinIO Community server)'
    licenseUrl = 'https://github.com/minio/minio/blob/master/LICENSE'
    pricingModel = 'FREE'
    categories = @('other')
    tags = @('storage', 's3', 'minio', 'development-only')
    icon = $IconId
    screenshots = @()
    highlights = [ordered]@{
      en = @('Explicit development-only release', 'Managed MinIO with generated credentials', 'External HTTPS S3-compatible mode')
      de = @('Ausdrücklich nur für Entwicklung', 'Verwaltetes MinIO mit generierten Zugangsdaten', 'Externer HTTPS-S3-Modus')
      it = @('Release esplicitamente solo per sviluppo', 'MinIO gestito con credenziali generate', 'Modalità S3 HTTPS esterna')
      fr = @('Version explicitement réservée au développement', 'MinIO géré avec identifiants générés', 'Mode S3 HTTPS externe')
    }
    releaseNotes = [ordered]@{
      en = 'Owner-authorized local development seed. MinIO Community is archived and contains known high-severity vulnerabilities. This approval is not production certification.'
      de = 'Vom Eigentümer autorisierter lokaler Entwicklungs-Seed. MinIO Community ist archiviert und enthält bekannte schwerwiegende Sicherheitslücken. Diese Freigabe ist keine Produktionszertifizierung.'
      it = 'Seed locale di sviluppo autorizzato dal proprietario. MinIO Community è archiviato e presenta vulnerabilità note ad alta gravità. Questa approvazione non è una certificazione per la produzione.'
      fr = 'Seed local de développement autorisé par le propriétaire. MinIO Community est archivé et contient des vulnérabilités graves connues. Cette approbation ne constitue pas une certification de production.'
    }
  }
}

if ($env:MARKETPLACE_DEV_SEED_PROFILE -ne 'local-development') {
  throw 'MinIO development seed is restricted to the explicit local-development Marketplace profile.'
}
if ((Split-Path -Leaf $marketplaceRoot) -ne 'Artio-Marketplace' -or
    (Split-Path -Leaf (Resolve-Path $CorePath).Path) -ne 'Artio') {
  throw 'The development seed requires the verified sibling Artio and Artio-Marketplace worktrees.'
}
if (-not (Test-Path -LiteralPath (Join-Path $stateDirectory 'publisher-token.txt')) -or
    -not (Test-Path -LiteralPath (Join-Path $stateDirectory 'admin-token.txt')) -or
    -not (Test-Path -LiteralPath (Join-Path $stateDirectory 'signing-ingest-token.txt')) -or
    -not (Test-Path -LiteralPath (Join-Path $stateDirectory 'release-signing-key.pem'))) {
  throw 'Local development release credentials have not been provisioned.'
}

$apiPort = [int](Read-LocalSetting 'MARKETPLACE_API_PORT')
$publicPort = [int](Read-LocalSetting 'MARKETPLACE_REGISTRY_PORT')
$publishPort = [int](Read-LocalSetting 'MARKETPLACE_REGISTRY_PUBLISH_PORT')
$publicHost = Read-LocalSetting 'MARKETPLACE_REGISTRY_HOST'
if ($apiPort -lt 1024 -or $publicPort -lt 1024 -or $publishPort -lt 1024 -or
    $publicHost -notmatch '^localhost:[0-9]{4,5}$') {
  throw 'Development seed accepts only the loopback Marketplace and OCI registry endpoints.'
}
$marketplaceUrl = "http://127.0.0.1:$apiPort"
$publicRegistryHost = "localhost:$publicPort"
$publisherRegistryHost = "localhost:$publishPort"

New-Item -ItemType Directory -Path $packageDirectory -Force | Out-Null
$manifestPath = Join-Path $packageDirectory 'manifest.json'
$catalogPath = Join-Path $packageDirectory 'catalog.json'
$iconPath = Join-Path $pluginRoot 'marketplace-icon.png'
if (-not (Test-Path -LiteralPath $iconPath)) { throw 'The MinIO Marketplace icon asset is missing from Core.' }
$manifest = Get-Content -Raw -LiteralPath (Join-Path $pluginRoot 'manifest-v2.template.json') | ConvertFrom-Json
$packageManifest = $manifest.packageManifest
if ($packageManifest.id -ne $pluginId -or $packageManifest.version -ne $version) {
  throw 'The MinIO manifest identity/version does not match the locked dev seed.'
}

$images = @(
  @{ Name = 'adapter'; Dockerfile = 'artio-plugins/minio-object-storage/Dockerfile'; Context = $pluginRoot; BuildArgs = @() },
  @{ Name = 'server'; Dockerfile = 'artio-plugins/minio-object-storage/minio-server.Dockerfile'; Context = $CorePath; BuildArgs = @() },
  @{ Name = 'provisioner'; Dockerfile = 'artio-plugins/minio-object-storage/provisioner/Dockerfile'; Context = (Join-Path $CorePath 'artio-plugins/minio-object-storage/provisioner'); BuildArgs = @() },
  @{ Name = 'provisioner-cleanup'; Dockerfile = 'artio-plugins/minio-object-storage/provisioner/Dockerfile'; Context = (Join-Path $CorePath 'artio-plugins/minio-object-storage/provisioner'); BuildArgs = @('--build-arg', 'LIFECYCLE_STAGE=cleanup') }
)
$digests = @{}
$publisherToken = (Get-Content -Raw -LiteralPath (Join-Path $stateDirectory 'publisher-token.txt')).Trim()
$registryPassword = (Get-Content -Raw -LiteralPath (Join-Path $stateDirectory 'zot-password.txt')).Trim()
try {
  $registryPassword | docker login $publisherRegistryHost --username publisher --password-stdin | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Could not authenticate to the local publisher OCI endpoint.' }
  foreach ($image in $images) {
    $stagingRepository = "staging/plugins/$pluginId/$($image.Name)"
    $tag = "$publisherRegistryHost/${stagingRepository}:$version"
    $dockerfile = if ([IO.Path]::IsPathRooted($image.Dockerfile)) { $image.Dockerfile } else { Join-Path $CorePath $image.Dockerfile }
    $build = @('build', '--platform', 'linux/amd64', '--file', $dockerfile, '--tag', $tag) + $image.BuildArgs + @($image.Context)
    Invoke-Docker $build "build $($image.Name)"
    $digests[$image.Name] = Push-Image $tag
    Write-Host "$($image.Name) digest: $($digests[$image.Name])"
  }
} finally {
  docker logout $publisherRegistryHost | Out-Null
  $publisherToken = $null
  $registryPassword = $null
}

$packageManifest.image = "$publicRegistryHost/public/plugins/$pluginId/adapter@$($digests.adapter)"
foreach ($service in $packageManifest.services) {
  switch ($service.id) {
    'minio' { $service.image = "$publicRegistryHost/public/plugins/$pluginId/server@$($digests.server)" }
    'provision' { $service.image = "$publicRegistryHost/public/plugins/$pluginId/provisioner@$($digests.provisioner)" }
    'finalize-rotation' { $service.image = "$publicRegistryHost/public/plugins/$pluginId/provisioner-cleanup@$($digests.'provisioner-cleanup')" }
  }
}
if ($packageManifest.services.Where({ $_.image -match 'REPLACE_WITH_PUBLISHED_IMAGE_DIGEST' }).Count -gt 0) {
  throw 'A bundle image reference was not replaced with a registry-derived digest.'
}
$manifest | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $manifestPath -Encoding utf8
$catalog = New-Catalog ('sha256-' + ('0' * 64))
$catalog | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $catalogPath -Encoding utf8

$publisherHeaders = @{ Authorization = "Bearer $((Get-Content -Raw -LiteralPath (Join-Path $stateDirectory 'publisher-token.txt')).Trim())" }
$catalogBody = $catalog | ConvertTo-Json -Depth 100 -Compress
$submission = @{ manifest = $manifest; catalog = $catalog } | ConvertTo-Json -Depth 100 -Compress
$validation = Invoke-JsonApi 'Post' "$marketplaceUrl/api/v1/admin/marketplace/releases/validate" $publisherHeaders $submission
if (-not $validation.valid) { throw "Marketplace rejected the MinIO dev seed: $($validation.errors -join '; ')" }
$manifestFileHash = Get-Sha256File $manifestPath
$requestKey = "dev-seed-$pluginId-$version-$manifestFileHash"
$releaseHeaders = $publisherHeaders + @{ 'Idempotency-Key' = $requestKey }
$release = Invoke-JsonApi 'Post' "$marketplaceUrl/api/v1/admin/marketplace/releases" $releaseHeaders $submission
$releaseId = [string]$release.releaseId
if (-not $releaseId) { throw 'Marketplace did not return a release identity for the MinIO dev seed.' }

if ($release.state -ne 'PUBLISHED') {
  $iconAssetId = Upload-Icon $releaseId $publisherHeaders.Authorization.Substring(7) $marketplaceUrl $iconPath
  $catalog = New-Catalog $iconAssetId
  $catalogBody = $catalog | ConvertTo-Json -Depth 100 -Compress
  Invoke-JsonApi 'Put' "$marketplaceUrl/api/v1/admin/marketplace/releases/$releaseId/catalog" $publisherHeaders $catalogBody | Out-Null

  $reviewHeaders = @{ Authorization = "Bearer $((Get-Content -Raw -LiteralPath (Join-Path $stateDirectory 'admin-token.txt')).Trim())" }
  $review = Invoke-RestMethod -Method Get -Uri "$marketplaceUrl/api/v1/admin/marketplace/releases/$releaseId/review" -Headers $reviewHeaders
  if ($review.approvalState -ne 'APPROVED') {
    $reason = 'OWNER_AUTHORIZED_DEV_SEED: local development only; owner explicitly accepts known MinIO Community vulnerabilities for isolated disposable test data; never production or customer data. Advisory references: GHSA-9c4q-hq6p-c237 and GHSA-hv4r-mvr4-25vw.'
    Invoke-JsonApi 'Post' "$marketplaceUrl/api/v1/admin/marketplace/releases/$releaseId/approve" $reviewHeaders (@{ reason = $reason } | ConvertTo-Json -Compress) | Out-Null
  }
  Invoke-RestMethod -Method Post -Uri "$marketplaceUrl/api/v1/admin/marketplace/releases/$releaseId/publish" -Headers $publisherHeaders | Out-Null
}

$openssl = $env:OPENSSL_BIN
if (-not $openssl -or -not (Test-Path -LiteralPath $openssl)) {
  $command = Get-Command openssl -ErrorAction SilentlyContinue
  if ($command) { $openssl = $command.Source }
  elseif (Test-Path -LiteralPath 'C:\Program Files\Git\usr\bin\openssl.exe') { $openssl = 'C:\Program Files\Git\usr\bin\openssl.exe' }
}
if (-not $openssl -or -not (Test-Path -LiteralPath $openssl)) { throw 'OpenSSL is required to sign the local MinIO release descriptor.' }
$signingToken = (Get-Content -Raw -LiteralPath (Join-Path $stateDirectory 'signing-ingest-token.txt')).Trim()
$signingHeaders = @{ Authorization = "Bearer $signingToken" }
$signatureDirectory = Join-Path $packageDirectory 'signature'
New-Item -ItemType Directory -Path $signatureDirectory -Force | Out-Null
$payloadPath = Join-Path $signatureDirectory 'descriptor.json'
$signaturePath = Join-Path $signatureDirectory 'signature.bin'
$envelopePath = Join-Path $signatureDirectory 'envelope.json'
Invoke-WebRequest -UseBasicParsing -Uri "$marketplaceUrl/api/v1/admin/marketplace/releases/$releaseId/signing-payload" -Headers $publisherHeaders -OutFile $payloadPath
& $openssl pkeyutl -sign -rawin -inkey (Join-Path $stateDirectory 'release-signing-key.pem') -in $payloadPath -out $signaturePath
if ($LASTEXITCODE -ne 0) { throw 'Could not sign the published MinIO descriptor with the local development trust key.' }
$signatureValue = [Convert]::ToBase64String([IO.File]::ReadAllBytes($signaturePath)).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$keyIdLine = Get-Content -LiteralPath (Join-Path $stateDirectory 'release-trusted-key.txt') | Select-Object -First 1
$keyId = ($keyIdLine -split '=', 2)[0]
$descriptor = Get-Content -Raw -LiteralPath $payloadPath | ConvertFrom-Json
$envelope = @{ descriptor = $descriptor; signature = @{ algorithm = 'Ed25519'; keyId = $keyId; value = $signatureValue } }
$envelope | ConvertTo-Json -Depth 30 -Compress | Set-Content -LiteralPath $envelopePath -Encoding utf8
$envelopeJson = Get-Content -Raw -LiteralPath $envelopePath
Invoke-JsonApi 'Post' "$marketplaceUrl/api/v1/admin/marketplace/releases/$releaseId/signature" $signingHeaders $envelopeJson | Out-Null

$publishedDescriptor = Invoke-RestMethod -Uri "$marketplaceUrl/api/v1/marketplace/plugins/$pluginId/releases/$version/descriptor"
if ($publishedDescriptor.signature.keyId -ne $keyId -or $publishedDescriptor.descriptor.version -ne $version) {
  throw 'Published MinIO descriptor signature or release identity could not be verified.'
}
$evidence = [ordered]@{
  pluginId = $pluginId
  version = $version
  releaseId = $releaseId
  approvalOrigin = 'OWNER_AUTHORIZED_DEV_SEED'
  environment = 'LOCAL_DEVELOPMENT_ONLY'
  knownDevelopmentRisk = @('GHSA-9c4q-hq6p-c237', 'GHSA-hv4r-mvr4-25vw')
  descriptorDigest = $publishedDescriptor.descriptor.manifestDigest
  imageDigests = [ordered]@{ adapter = $digests.adapter; server = $digests.server; provisioner = $digests.provisioner; rotationCleanup = $digests.'provisioner-cleanup' }
  references = @($packageManifest.image) + @($packageManifest.services | ForEach-Object { $_.image })
  seededAtUtc = [DateTime]::UtcNow.ToString('O')
}
$evidence | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $releaseEvidencePath -Encoding utf8
Write-Host "Owner-authorized development release is APPROVED and installable: $pluginId@$version"
Write-Host "Release ID: $releaseId"
Write-Host "Evidence: $releaseEvidencePath"
