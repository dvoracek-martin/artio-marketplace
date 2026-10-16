[CmdletBinding()]
param([Parameter(Mandatory)][string]$MarketplaceUrl,[Parameter(Mandatory)][string]$PluginId,[Parameter(Mandatory)][string]$Version)
$ErrorActionPreference='Stop';$base=$MarketplaceUrl.TrimEnd('/')
$manifest=Invoke-RestMethod -Uri "$base/api/v1/marketplace/plugins/$PluginId/releases/$Version/manifest"
$runtimeManifest=if($manifest.packageManifest){$manifest.packageManifest}else{$manifest}
$images=@($runtimeManifest.image)+@($runtimeManifest.services|ForEach-Object{$_.image})
foreach($image in $images){Write-Host "Pulling $image";docker pull $image;if($LASTEXITCODE-ne 0){throw "docker pull failed for $image"}}
Write-Host "Public manifest and all image pulls succeeded for $PluginId $Version."
