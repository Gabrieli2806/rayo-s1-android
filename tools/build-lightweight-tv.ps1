# Build the local UI with a separate development key; never install to a device.
$ErrorActionPreference='Stop'
$rayoRepo=Split-Path -Parent $PSScriptRoot
$rayoSecrets=Join-Path $rayoRepo 'local/secrets'
$rayoKey=Join-Path $rayoSecrets 'launcher-android12-development.p12'
$rayoPassword=Join-Path $rayoSecrets 'launcher-android12-development-password.txt'
New-Item -ItemType Directory -Force $rayoSecrets | Out-Null
if(-not (Test-Path -LiteralPath $rayoKey)){
    if(Test-Path -LiteralPath $rayoPassword){throw 'Existing password without its key; inspect local secrets before regenerating'}
    $rayoRandom=New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($rayoRandom)
    [IO.File]::WriteAllText($rayoPassword,[Convert]::ToBase64String($rayoRandom))
    & keytool -genkeypair -alias rayo12-development -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=Rayo Android 12 Development' -storetype PKCS12 -keystore $rayoKey -storepass:file $rayoPassword -keypass:file $rayoPassword
    if($LASTEXITCODE -ne 0){throw 'Development key generation failed'}
}
if(-not (Test-Path -LiteralPath $rayoPassword)){throw 'Copy the development signing password with its key'}
$rayoPreviousPassword=$env:RAYO_SIGNING_PASSWORD
try{
    $env:RAYO_SIGNING_PASSWORD=[IO.File]::ReadAllText($rayoPassword)
    & (Join-Path $PSScriptRoot 'build-launcher.ps1') -Keystore $rayoKey
}finally{
    if($null -eq $rayoPreviousPassword){Remove-Item Env:RAYO_SIGNING_PASSWORD -ErrorAction SilentlyContinue}
    else{$env:RAYO_SIGNING_PASSWORD=$rayoPreviousPassword}
}
$rayoApk=Join-Path $rayoRepo 'outputs/Rayo-Dark-TV.apk'
@{package='local.rayo.launcher';sha256=(Get-FileHash -Algorithm SHA256 $rayoApk).Hash.ToLowerInvariant();development_key=$true;google_services=$false}|ConvertTo-Json|Set-Content -Encoding utf8 (Join-Path $rayoRepo 'outputs/Rayo-Dark-TV.build.json')
