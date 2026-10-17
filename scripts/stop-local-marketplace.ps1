[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$repoRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$state=Join-Path $repoRoot '.local-marketplace'
$envFile=Join-Path $state '.env'
if(-not(Test-Path -LiteralPath $envFile)){Write-Host 'Marketplace local state does not exist; nothing to stop.';exit 0}
foreach($line in Get-Content -LiteralPath $envFile){if($line -match '^([A-Z0-9_]+)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}}
$composeArguments=@('--project-name','artio-marketplace-local','--env-file',$envFile,'-f',(Join-Path $repoRoot 'deployment/compose.yaml'),'-f',(Join-Path $repoRoot 'deployment/compose.dev.yaml'),'down','--remove-orphans')
& docker compose @composeArguments
if($LASTEXITCODE -ne 0){throw 'Marketplace did not stop cleanly; persistent volumes were retained.'}
Write-Host 'Marketplace containers and project network stopped. PostgreSQL, Zot, asset, and quarantine volumes were kept.'
