<#
.SYNOPSIS
  Builds and publishes a PocketShell release (docs/DESIGN.md section 11.5).

.DESCRIPTION
  1. version.properties: versionName=<Version>, versionCode=<previous + 1>
  2. host: dotnet publish psh (server/CLI) + PocketShell (tray) into one folder (win-x64, framework-dependent)
  3. Android: gradlew assembleRelease (release is signed with the same key as v0.1.0, the default debug
     keystore %USERPROFILE%\.android\debug.keystore) -> pocketshell-android-v<Version>.apk; signer certificate is
     checked against that keystore so in-place app updates keep working
  4. SHA256SUMS.txt ("<sha256>  <name>")
  5. git commit + tag v<Version> + push, then `gh release create` (prerelease when the version has a '-')

  -DryRun performs steps 1-4 into a temp folder only: version.properties is restored afterwards and nothing is
  committed, tagged, pushed or released.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\release.ps1 -Version 0.1.1-alpha -DryRun
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [switch]$DryRun,
    [string]$OutDir
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3

$SemVerPattern = '^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-((0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*)(\.(0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?$'
if ($Version -notmatch $SemVerPattern) { throw "Version '$Version' is not SemVer 2.0 (x.y.z[-pre], no build metadata)" }
$IsPrerelease = $Version.Contains('-')

$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$VersionFile = Join-Path $Root 'version.properties'
$JavaHome = 'C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot'
$DebugKeystore = Join-Path $env:USERPROFILE '.android\debug.keystore'
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Step([string]$msg) { Write-Host "==> $msg" -ForegroundColor Cyan }

function Invoke-Checked([string]$file, [string[]]$arguments, [string]$workDir = $Root) {
    Write-Host "    > $file $($arguments -join ' ')" -ForegroundColor DarkGray
    Push-Location $workDir
    try {
        & $file @arguments
        if ($LASTEXITCODE -ne 0) { throw "$file exited with code $LASTEXITCODE" }
    }
    finally { Pop-Location }
}

function Read-VersionProps {
    $props = @{}
    foreach ($line in [System.IO.File]::ReadAllLines($VersionFile)) {
        if ($line -match '^\s*([A-Za-z]+)\s*=\s*(.*?)\s*$') { $props[$Matches[1]] = $Matches[2] }
    }
    if (-not $props.ContainsKey('versionName') -or -not $props.ContainsKey('versionCode')) { throw "version.properties needs versionName and versionCode" }
    return $props
}

function Get-Sha256([string]$path) { (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLowerInvariant() }

function Get-CertSha256FromKeytool([string[]]$keytoolArgs) {
    $keytool = Join-Path $JavaHome 'bin\keytool.exe'
    # keytool may print warnings on stderr; with 2>&1 under 'Stop' Windows PowerShell 5.1 would treat them as errors.
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $out = & $keytool @keytoolArgs 2>&1 | Out-String }
    finally { $ErrorActionPreference = $prev }
    if ($LASTEXITCODE -ne 0) { throw "keytool failed: $out" }
    $m = [regex]::Match($out, 'SHA256:\s*([0-9A-Fa-f:]{95})')
    if (-not $m.Success) { throw "keytool output has no SHA256 fingerprint: $out" }
    return $m.Groups[1].Value.Replace(':', '').ToLowerInvariant()
}

# APKs for minSdk 26+ carry only v2/v3 signatures (no JAR v1), so keytool -printcert cannot read them; use apksigner.
function Get-ApkSignerSha256([string]$apkPath) {
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $bt = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'apksigner.bat') } |
        Sort-Object { [version]($_.Name -replace '[^0-9.].*$', '') } -Descending | Select-Object -First 1
    if (-not $bt) { throw "apksigner not found under $sdk\build-tools (needed to verify APK signature continuity)" }
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $out = & (Join-Path $bt.FullName 'apksigner.bat') verify --print-certs $apkPath 2>&1 | Out-String }
    finally { $ErrorActionPreference = $prev }
    if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed: $out" }
    $m = [regex]::Match($out, 'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})')
    if (-not $m.Success) { throw "apksigner output has no signer SHA-256: $out" }
    return $m.Groups[1].Value.ToLowerInvariant()
}

# ------------------------------------------------------------------ preflight
if (-not (Test-Path $VersionFile)) { throw "missing $VersionFile" }
if (-not (Test-Path (Join-Path $JavaHome 'bin\java.exe'))) { throw "JDK 17 not found at $JavaHome" }
if (-not (Test-Path $DebugKeystore)) { throw "signing keystore not found: $DebugKeystore (v0.1.0 was signed with it; a different key breaks in-place updates)" }
if (-not $DryRun) {
    $dirty = (& git -C $Root status --porcelain) | Out-String
    if ($dirty.Trim().Length -gt 0) { throw "working tree is not clean; commit or stash first:`n$dirty" }
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw "GitHub CLI (gh) not found on PATH" }
    & git -C $Root rev-parse -q --verify "refs/tags/v$Version" *> $null
    if ($LASTEXITCODE -eq 0) { throw "tag v$Version already exists" }
}

$old = Read-VersionProps
$newCode = [int]$old['versionCode'] + 1
if (-not $OutDir) { $OutDir = Join-Path ([System.IO.Path]::GetTempPath()) ("pocketshell-release-v$Version-" + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$OutDir = [System.IO.Path]::GetFullPath($OutDir)
$Work = Join-Path $OutDir '_work'
New-Item -ItemType Directory -Force -Path $Work | Out-Null
$originalVersionText = [System.IO.File]::ReadAllText($VersionFile)

Write-Host "PocketShell release v$Version (versionCode $($old['versionCode']) -> $newCode, prerelease=$IsPrerelease, dryRun=$DryRun)"
Write-Host "output: $OutDir"

$hostZipName = "pocketshell-host-win-x64-v$Version.zip"
$apkName = "pocketshell-android-v$Version.apk"
$hostZip = Join-Path $OutDir $hostZipName
$apkOut = Join-Path $OutDir $apkName
$sumsPath = Join-Path $OutDir 'SHA256SUMS.txt'

try {
    # -------------------------------------------------------------- 1. version.properties
    Step "version.properties: versionName=$Version versionCode=$newCode"
    [System.IO.File]::WriteAllText($VersionFile, "versionName=$Version`nversionCode=$newCode`n", $Utf8NoBom)

    # -------------------------------------------------------------- 2. host publish + zip
    Step "host: dotnet publish win-x64 framework-dependent"
    $pub = Join-Path $Work 'host'
    Invoke-Checked 'dotnet' @('publish', (Join-Path $Root 'host\src\Mpta\Mpta.csproj'), '-c', 'Release', '-r', 'win-x64',
        '--self-contained', 'false', '-o', $pub, '--artifacts-path', (Join-Path $Work 'artifacts'), '-nologo')
    Invoke-Checked 'dotnet' @('publish', (Join-Path $Root 'host\src\PocketShell.Tray\PocketShell.Tray.csproj'), '-c', 'Release', '-r', 'win-x64',
        '--self-contained', 'false', '-o', $pub, '--artifacts-path', (Join-Path $Work 'artifacts'), '-nologo')
    foreach ($exe in 'psh', 'PocketShell') {
        if (-not (Test-Path (Join-Path $pub "$exe.exe"))) { throw "publish output has no $exe.exe" }
        $pv = [System.Diagnostics.FileVersionInfo]::GetVersionInfo((Join-Path $pub "$exe.dll")).ProductVersion
        if ($pv.Split('+')[0] -ne $Version) { throw "published $exe.dll reports version '$pv', expected $Version" }
    }
    Get-ChildItem $pub -Filter '*.pdb' | Remove-Item -Force
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    if (Test-Path $hostZip) { Remove-Item -Force $hostZip }
    [System.IO.Compression.ZipFile]::CreateFromDirectory($pub, $hostZip, [System.IO.Compression.CompressionLevel]::Optimal, $false)
    Write-Host "    $hostZipName ($([math]::Round((Get-Item $hostZip).Length / 1MB, 2)) MB)"

    # -------------------------------------------------------------- 3. APK
    Step "android: gradlew assembleRelease (signed with $DebugKeystore)"
    $env:JAVA_HOME = $JavaHome
    $android = Join-Path $Root 'android'
    Invoke-Checked 'cmd.exe' @('/c', (Join-Path $android 'gradlew.bat'), 'assembleRelease', '--no-daemon', '--console=plain',
        '-Pkotlin.compiler.execution.strategy=in-process') $android
    $apkDir = Join-Path $android 'app\build\outputs\apk\release'
    $apk = Get-ChildItem $apkDir -Filter '*.apk' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $apk) { throw "no APK in $apkDir" }
    if ($apk.Name -like '*unsigned*') { throw "release APK is unsigned ($($apk.Name)); release signingConfig must use the debug keystore" }
    Copy-Item -Force $apk.FullName $apkOut

    # Signature continuity: APK signer cert must equal the debug keystore cert used for v0.1.0.
    $expectedCert = Get-CertSha256FromKeytool @('-list', '-v', '-keystore', $DebugKeystore, '-storepass', 'android', '-alias', 'androiddebugkey')
    $apkCert = Get-ApkSignerSha256 $apkOut
    if ($apkCert -ne $expectedCert) { throw "APK signer $apkCert != debug keystore cert $expectedCert (in-place update would fail)" }
    Write-Host "    $apkName signer sha256=$apkCert (matches debug keystore)"

    # -------------------------------------------------------------- 4. SHA256SUMS.txt
    Step "SHA256SUMS.txt"
    $lines = @("$(Get-Sha256 $hostZip)  $hostZipName", "$(Get-Sha256 $apkOut)  $apkName")
    [System.IO.File]::WriteAllText($sumsPath, ($lines -join "`n") + "`n", $Utf8NoBom)
    $lines | ForEach-Object { Write-Host "    $_" }
}
catch {
    [System.IO.File]::WriteAllText($VersionFile, $originalVersionText, $Utf8NoBom)
    Write-Host "release failed; version.properties restored" -ForegroundColor Red
    throw
}
finally {
    if (Test-Path $Work) { Remove-Item -Recurse -Force $Work -ErrorAction SilentlyContinue }
}

if ($DryRun) {
    [System.IO.File]::WriteAllText($VersionFile, $originalVersionText, $Utf8NoBom)
    Step "dry run complete: version.properties restored; no commit/tag/push/release"
    Get-ChildItem $OutDir | Format-Table Name, Length -AutoSize | Out-String | Write-Host
    return
}

# ------------------------------------------------------------------ 5. commit, tag, push, GitHub release
Step "git commit + tag v$Version + push"
Invoke-Checked 'git' @('-C', $Root, 'add', 'version.properties')
Invoke-Checked 'git' @('-C', $Root, 'commit', '-m', "release v$Version")
Invoke-Checked 'git' @('-C', $Root, 'tag', '-a', "v$Version", '-m', "PocketShell v$Version")
Invoke-Checked 'git' @('-C', $Root, 'push')
Invoke-Checked 'git' @('-C', $Root, 'push', 'origin', "v$Version")

Step "gh release create v$Version"
$ghArgs = @('release', 'create', "v$Version", $hostZip, $apkOut, $sumsPath, '--title', "PocketShell v$Version", '--generate-notes')
if ($IsPrerelease) { $ghArgs += '--prerelease' }
Invoke-Checked 'gh' $ghArgs
Step "released v$Version (artifacts in $OutDir)"
