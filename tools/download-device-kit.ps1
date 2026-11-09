param([string]$Repository='Gabrieli2806/rayo-s1-android')
$ErrorActionPreference='Stop'
$rayoRoot=Split-Path -Parent $PSScriptRoot
$rayoDescriptor=Get-Content (Join-Path $rayoRoot 'manifests/device-kit.json') -Raw | ConvertFrom-Json
$rayoDownloads=Join-Path $rayoRoot 'local/downloads'
$rayoDestination=Join-Path $rayoRoot 'local/device-kit'
if(Test-Path $rayoDestination){throw 'local/device-kit already exists; preserve or rename it before downloading another checkpoint'}
New-Item -ItemType Directory -Force $rayoDownloads | Out-Null
& gh release download $rayoDescriptor.release --repo $Repository --pattern $rayoDescriptor.asset --dir $rayoDownloads --clobber
if($LASTEXITCODE -ne 0){throw 'Private release download failed; check gh auth status'}
$rayoArchive=Join-Path $rayoDownloads $rayoDescriptor.asset
if((Get-FileHash $rayoArchive -Algorithm SHA256).Hash.ToLower() -ne $rayoDescriptor.sha256){throw 'Device kit checksum mismatch'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::ExtractToDirectory($rayoArchive,$rayoDestination)
Write-Output ('Verified kit extracted to '+$rayoDestination)
