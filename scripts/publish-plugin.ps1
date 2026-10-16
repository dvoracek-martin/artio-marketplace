[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Medium')]
param([Parameter(Mandatory)][string]$Package,[Parameter(Mandatory)][string]$MarketplaceUrl,[Parameter(Mandatory)][string]$TokenFile)
$ErrorActionPreference='Stop'
$manifestPath=Join-Path $Package 'manifest.json';$catalogPath=Join-Path $Package 'catalog.json'
if(!(Test-Path -LiteralPath $manifestPath)-or !(Test-Path -LiteralPath $catalogPath)){throw 'Package requires manifest.json and catalog.json.'}
$manifest=Get-Content -Raw -LiteralPath $manifestPath|ConvertFrom-Json -AsHashtable
$catalog=Get-Content -Raw -LiteralPath $catalogPath|ConvertFrom-Json -AsHashtable
foreach($image in @($manifest.image)+@($manifest.services|ForEach-Object{$_.image})){if($image -notmatch '^[a-zA-Z0-9.-]+(?::[0-9]{1,5})?/[a-zA-Z0-9._/-]+@sha256:[a-f0-9]{64}$'){throw "Image must use a lowercase sha256 digest: $image"}}
$token=(Get-Content -Raw -LiteralPath $TokenFile).Trim();if($token.Length-lt 32){throw 'Publisher token file must contain at least 32 characters.'}
$headers=@{Authorization="Bearer $token"};$base=$MarketplaceUrl.TrimEnd('/')
$iconFile=Join-Path $Package ([string]$catalog.icon);if(!(Test-Path -LiteralPath $iconFile -PathType Leaf)){throw "Icon missing: $($catalog.icon)"}
$shotFiles=@();foreach($shot in $catalog.screenshots){$file=Join-Path $Package ([string]$shot.file);if(!(Test-Path -LiteralPath $file -PathType Leaf)){throw "Screenshot missing: $($shot.file)"};$shotFiles+=$file}
$catalog.icon='sha256-'+('0'*64);for($i=0;$i-lt $catalog.screenshots.Count;$i++){$catalog.screenshots[$i].assetId='sha256-'+('0'*64);$catalog.screenshots[$i].Remove('file')}
$submission=@{manifest=$manifest;catalog=$catalog};$body=$submission|ConvertTo-Json -Depth 100 -Compress
$validate=Invoke-RestMethod -Method Post -Uri "$base/api/v1/admin/marketplace/releases/validate" -Headers $headers -ContentType 'application/json' -Body $body
if(!$validate.valid){throw "Marketplace validation failed: $($validate.errors -join '; ')"}
$manifestHash=(Get-FileHash -Algorithm SHA256 -LiteralPath $manifestPath).Hash.ToLowerInvariant();$key="publish-$($manifest.id)-$($manifest.version)-$manifestHash"
if(!$PSCmdlet.ShouldProcess("$($manifest.id) $($manifest.version)",'Create release, upload assets and stage for separate admin review')){Write-Host 'Validation passed; WhatIf prevented staging.';return}
$draft=Invoke-RestMethod -Method Post -Uri "$base/api/v1/admin/marketplace/releases" -Headers ($headers+@{'Idempotency-Key'=$key}) -ContentType 'application/json' -Body $body;$release=$draft.releaseId
if($draft.state -eq 'PUBLISHED'){Write-Host "Already published $($manifest.id) $($manifest.version) (idempotent retry).";return}
$client=[System.Net.Http.HttpClient]::new();$client.DefaultRequestHeaders.Authorization=[System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$token)
try{
 $upload={param([string]$file,[string]$kind)$form=[System.Net.Http.MultipartFormDataContent]::new();$stream=[System.IO.File]::OpenRead($file);$part=[System.Net.Http.StreamContent]::new($stream);$part.Headers.ContentType=[System.Net.Http.Headers.MediaTypeHeaderValue]::new('application/octet-stream');$form.Add($part,'file',[System.IO.Path]::GetFileName($file));try{$response=$client.PostAsync("$base/api/v1/admin/marketplace/releases/$release/assets?kind=$kind",$form).GetAwaiter().GetResult();$text=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult();if(!$response.IsSuccessStatusCode){throw "Asset upload failed ($([int]$response.StatusCode)): $text"};return $text|ConvertFrom-Json -AsHashtable}finally{$form.Dispose();$stream.Dispose()}}
 $catalog.icon=(& $upload $iconFile icon).assetId
 for($i=0;$i-lt $shotFiles.Count;$i++){$catalog.screenshots[$i].assetId=(& $upload $shotFiles[$i] screenshot).assetId}
 $catBody=$catalog|ConvertTo-Json -Depth 100 -Compress
 Invoke-RestMethod -Method Put -Uri "$base/api/v1/admin/marketplace/releases/$release/catalog" -Headers $headers -ContentType 'application/json' -Body $catBody|Out-Null
 Write-Host "Staged $($manifest.id) $($manifest.version) for separate admin review."
 Write-Host "Release ID: $release"
 Write-Host "Manifest digest: $($validate.manifestDigest)"
}finally{$client.Dispose();$token=$null;$headers=$null}
