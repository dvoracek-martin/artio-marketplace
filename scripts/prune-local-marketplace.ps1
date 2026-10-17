[CmdletBinding()]
param([switch]$Confirmed)

$ErrorActionPreference = 'Stop'
if (-not $Confirmed) {
  throw 'This script permanently deletes the local Marketplace database, registry, credentials, releases, and built images. Run it only through Stop-And-Prune-Artio.cmd after confirmation.'
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$repoLeaf = Split-Path -Leaf $repoRoot
if ($repoLeaf -ne 'Artio-Marketplace' -or
    -not (Test-Path -LiteralPath (Join-Path $repoRoot '.git')) -or
    -not (Test-Path -LiteralPath (Join-Path $repoRoot 'deployment/compose.yaml'))) {
  throw 'Refusing to prune Marketplace resources outside the verified Artio-Marketplace worktree.'
}

$projectName = 'artio-marketplace-local'
$stateDirectory = Join-Path $repoRoot '.local-marketplace'
$stateTarget = [IO.Path]::GetFullPath($stateDirectory).TrimEnd('\')
$expectedStateTarget = [IO.Path]::GetFullPath((Join-Path $repoRoot '.local-marketplace')).TrimEnd('\')
if (-not $stateTarget.Equals($expectedStateTarget, [StringComparison]::OrdinalIgnoreCase) -or
    (Split-Path -Leaf $stateTarget) -ne '.local-marketplace' -or
    $stateTarget.Equals([IO.Path]::GetFullPath($repoRoot).TrimEnd('\'), [StringComparison]::OrdinalIgnoreCase)) {
  throw 'Refusing to remove an unexpected Marketplace state directory.'
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
  throw 'Docker is required to prune the local Marketplace.'
}

$composeFile = Join-Path $repoRoot 'deployment/compose.yaml'
$composeDevFile = Join-Path $repoRoot 'deployment/compose.dev.yaml'
$envFile = Join-Path $stateDirectory '.env'
if (Test-Path -LiteralPath $envFile) {
  $composeArgs = @(
    'compose', '--project-name', $projectName, '--env-file', $envFile,
    '-f', $composeFile, '-f', $composeDevFile,
    'down', '--volumes', '--remove-orphans', '--rmi', 'local'
  )
  & docker @composeArgs
  if ($LASTEXITCODE -ne 0) { throw 'Marketplace Compose cleanup failed.' }
}

# Compose down handles the normal path. Remove any remaining resources carrying
# the exact project label so interrupted starts and orphaned containers are covered.
$projectLabel = "com.docker.compose.project=$projectName"
$remainingContainers = @(& docker ps -aq --filter "label=$projectLabel")
if ($LASTEXITCODE -ne 0) { throw 'Could not enumerate Marketplace containers.' }
if ($remainingContainers.Count -gt 0) {
  & docker rm -f @remainingContainers
  if ($LASTEXITCODE -ne 0) { throw 'Could not remove all Marketplace containers.' }
}

$remainingNetworks = @(& docker network ls -q --filter "label=$projectLabel")
if ($LASTEXITCODE -ne 0) { throw 'Could not enumerate Marketplace networks.' }
if ($remainingNetworks.Count -gt 0) {
  & docker network rm @remainingNetworks
  if ($LASTEXITCODE -ne 0) { throw 'Could not remove all Marketplace networks.' }
}

$remainingVolumes = @(& docker volume ls -q --filter "label=$projectLabel")
if ($LASTEXITCODE -ne 0) { throw 'Could not enumerate Marketplace volumes.' }
if ($remainingVolumes.Count -gt 0) {
  & docker volume rm @remainingVolumes
  if ($LASTEXITCODE -ne 0) { throw 'Could not remove all Marketplace volumes.' }
}

# Remove images built/pulled through this local Marketplace registry. Keep any
# image still referenced by a container from another Docker project.
$registryPrefixes = @()
if (Test-Path -LiteralPath $envFile) {
  $settings = @{}
  foreach ($line in Get-Content -LiteralPath $envFile) {
    if ($line -match '^([A-Z0-9_]+)=(.*)$') { $settings[$Matches[1]] = $Matches[2] }
  }
  if ($settings.MARKETPLACE_REGISTRY_PORT -match '^\d{4,5}$') {
    $registryPrefixes += "localhost:$($settings.MARKETPLACE_REGISTRY_PORT)/public/plugins/"
  }
  if ($settings.MARKETPLACE_REGISTRY_PUBLISH_PORT -match '^\d{4,5}$') {
    $registryPrefixes += "localhost:$($settings.MARKETPLACE_REGISTRY_PUBLISH_PORT)/staging/plugins/"
  }
}
$connectionFile = Join-Path $stateDirectory 'connection.txt'
if (Test-Path -LiteralPath $connectionFile) {
  foreach ($line in Get-Content -LiteralPath $connectionFile) {
    if ($line -match '^Public OCI registry:\s*(localhost:\d{4,5})$') {
      $registryPrefixes += "$($Matches[1])/public/plugins/"
    } elseif ($line -match '^Publisher OCI registry:\s*(localhost:\d{4,5})$') {
      $registryPrefixes += "$($Matches[1])/staging/plugins/"
    }
  }
}

$imageRows = @(& docker image ls --digests --format '{{.Repository}}|{{.Tag}}|{{.Digest}}')
if ($LASTEXITCODE -ne 0) { throw 'Could not enumerate local Docker images.' }
$imageReferences = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($row in $imageRows) {
  $fields = $row -split '\|', 3
  if ($fields.Count -ne 3) { continue }
  $repository, $tag, $digest = $fields
  $isLocalMarketplaceImage = $repository -eq 'artio-marketplace'
  $isMarketplacePluginImage = $false
  foreach ($prefix in $registryPrefixes) {
    if ($repository.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
      $isMarketplacePluginImage = $true
      break
    }
  }
  if (-not ($isLocalMarketplaceImage -or $isMarketplacePluginImage)) { continue }
  if ($digest -and $digest -ne '<none>') {
    [void]$imageReferences.Add("${repository}@${digest}")
  } elseif ($tag -and $tag -ne '<none>') {
    [void]$imageReferences.Add("${repository}:${tag}")
  }
}

foreach ($imageReference in $imageReferences) {
  $users = @(& docker ps -aq --filter "ancestor=$imageReference")
  if ($LASTEXITCODE -ne 0) { throw "Could not check Docker image use for $imageReference." }
  if ($users.Count -gt 0) {
    Write-Warning "Keeping Marketplace image still used by a Docker container: $imageReference"
    continue
  }
  & docker image rm $imageReference *> $null
  if ($LASTEXITCODE -ne 0) { throw "Could not remove Marketplace image $imageReference." }
}

if (Test-Path -LiteralPath $stateTarget) {
  Remove-Item -LiteralPath $stateTarget -Recurse -Force
}

Write-Host 'Local Marketplace containers, networks, volumes, registry images, and generated credentials/state have been removed.'
