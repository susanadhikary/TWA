<#
=============================================================================
 TapTill KDS - configure and build in one step (Windows PowerShell)

 Interactive (asks for everything, shows current values as defaults):
     .\kds.ps1

 Non-interactive examples:
     .\kds.ps1 -Url https://pos.example.com/kds
     .\kds.ps1 -Url https://pos.example.com/kds -Scope https://pos.example.com/ -Name "TapTill KDS"
     .\kds.ps1 -Url https://pos.example.com/kds -Keystore C:\keys\taptill-kds-release.jks
     .\kds.ps1 -Show

 Parameters:
     -Url URL            Page the app opens (https:// only)
     -Scope URL          App scope; pages under it get notifications, camera, ...
                         Default: the whole site of -Url (https://host/)
     -Name NAME          App name on the TV home screen
     -VersionName X      Version shown to people (default: bump last number, 1.1.0 -> 1.1.1)
     -NoBump             Keep VERSION_CODE / VERSION_NAME as they are
     -Build TYPE         release (default), debug, or none (only update kds.properties)
     -Keystore FILE      Sign the release with this keystore (or set KDS_KEYSTORE_PATH)
     -SaveRelease        Also copy the signed APK into releases\ and update SHA256SUMS
     -Show               Print the current configuration and exit
     -Yes                Don't ask for confirmation

 If Windows blocks the script, run it once with:
     powershell -ExecutionPolicy Bypass -File .\kds.ps1

 Full guide: docs\CHANGE-URL-AND-REBUILD.md
=============================================================================
#>
[CmdletBinding()]
param(
    [string]$Url = "",
    [string]$Scope = "",
    [string]$Name = "",
    [string]$VersionName = "",
    [switch]$NoBump,
    [ValidateSet("release", "debug", "none")]
    [string]$Build = "release",
    [string]$Keystore = $env:KDS_KEYSTORE_PATH,
    [switch]$SaveRelease,
    [switch]$Show,
    [switch]$Yes
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Props = Join-Path $Root "kds.properties"
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Write-Ok($msg)   { Write-Host "OK  $msg" -ForegroundColor Green }
function Write-Warn($msg) { Write-Host "!   $msg" -ForegroundColor Yellow }
function Stop-Kds($msg)   { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }

# ---------------------------------------------------------------- kds.properties

function Get-Prop([string]$Key) {
    if (-not (Test-Path $Props)) { Stop-Kds "kds.properties not found in $Root" }
    foreach ($line in [System.IO.File]::ReadAllLines($Props, $Utf8NoBom)) {
        if ($line.StartsWith("$Key=")) { return $line.Substring($Key.Length + 1).Trim() }
    }
    return ""
}

function Set-Prop([string]$Key, [string]$Value) {
    $lines = New-Object System.Collections.Generic.List[string]
    $done = $false
    foreach ($line in [System.IO.File]::ReadAllLines($Props, $Utf8NoBom)) {
        if ($line.StartsWith("$Key=")) { $lines.Add("$Key=$Value"); $done = $true }
        else { $lines.Add($line) }
    }
    if (-not $done) { $lines.Add("$Key=$Value") }
    [System.IO.File]::WriteAllText($Props, (($lines -join "`n") + "`n"), $Utf8NoBom)
}

function Show-Config {
    Write-Host "Current configuration (kds.properties)" -ForegroundColor Cyan
    Write-Host "  URL          : $(Get-Prop 'KDS_URL')"
    Write-Host "  Scope        : $(Get-Prop 'KDS_SCOPE')"
    Write-Host "  App name     : $(Get-Prop 'APP_NAME')"
    Write-Host "  Version code : $(Get-Prop 'VERSION_CODE')"
    Write-Host "  Version name : $(Get-Prop 'VERSION_NAME')"
}

# ---------------------------------------------------------------- URL checks

# Returns @{ Origin; Path } for an https URL, or $null.
function ConvertFrom-HttpsUrl([string]$Value) {
    $m = [regex]::Match($Value, '^(?i)https://([^/?#]+)([^?#]*)')
    if (-not $m.Success -or -not $m.Groups[1].Value) { return $null }
    $path = $m.Groups[2].Value
    if (-not $path) { $path = "/" }
    return @{ Origin = "https://" + $m.Groups[1].Value.ToLowerInvariant(); Path = $path }
}

# Returns $null when valid, otherwise the reason.
function Test-UrlScope([string]$U, [string]$S) {
    $pu = ConvertFrom-HttpsUrl $U
    if (-not $pu) { return "URL must be a full https:// address (got: $U)" }
    $ps = ConvertFrom-HttpsUrl $S
    if (-not $ps) { return "Scope must be a full https:// address (got: $S)" }
    if ($pu.Origin -ne $ps.Origin) { return "URL ($U) and scope ($S) must be on the same https host" }
    if (-not $pu.Path.StartsWith($ps.Path, [System.StringComparison]::Ordinal)) {
        return "URL path ($($pu.Path)) must start with the scope path ($($ps.Path))"
    }
    return $null
}

function Get-DefaultScope([string]$U) {
    $pu = ConvertFrom-HttpsUrl $U
    if ($pu) { return $pu.Origin + "/" }
    return ""
}

function Step-VersionName([string]$V) {
    $m = [regex]::Match($V, '^(.*?)(\d+)$')
    if ($m.Success) { return $m.Groups[1].Value + ([int64]$m.Groups[2].Value + 1) }
    return $V
}

function Read-WithDefault([string]$Question, [string]$Default) {
    $reply = Read-Host "$Question [$Default]"
    if ([string]::IsNullOrWhiteSpace($reply)) { return $Default }
    return $reply.Trim()
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -Algorithm SHA256 -Path $Path).Hash.ToLowerInvariant()
}

# ---------------------------------------------------------------- main

if ($Show) { Show-Config; exit 0 }

$Interactive = -not ($Url -or $Scope -or $Name)
$CurUrl = Get-Prop "KDS_URL"
$CurScope = Get-Prop "KDS_SCOPE"
$CurName = Get-Prop "APP_NAME"
$CurCode = Get-Prop "VERSION_CODE"
$CurVName = Get-Prop "VERSION_NAME"
if ($CurCode -notmatch '^\d+$') { Stop-Kds "VERSION_CODE in kds.properties is not a number: $CurCode" }

Write-Host ""
Write-Host "TapTill KDS - configure & build" -ForegroundColor Cyan
Show-Config
Write-Host ""

if ($Interactive) {
    $Url = Read-WithDefault "URL the app should open" $CurUrl
    if ($CurScope -and -not (Test-UrlScope $Url $CurScope)) { $suggested = $CurScope } else { $suggested = Get-DefaultScope $Url }
    $Scope = Read-WithDefault "Scope (pages that get notifications/camera/location)" $suggested
    $Name = Read-WithDefault "App name" $CurName
} else {
    if (-not $Url) { $Url = $CurUrl }
    if (-not $Scope) {
        if ($CurScope -and -not (Test-UrlScope $Url $CurScope)) { $Scope = $CurScope } else { $Scope = Get-DefaultScope $Url }
    }
    if (-not $Name) { $Name = $CurName }
}

$problem = Test-UrlScope $Url $Scope
if ($problem) { Stop-Kds $problem }
if ([string]::IsNullOrWhiteSpace($Name)) { Stop-Kds "App name cannot be empty" }

if ($NoBump) {
    $NewCode = [int64]$CurCode
    if ($VersionName) { $NewVName = $VersionName } else { $NewVName = $CurVName }
} else {
    $NewCode = [int64]$CurCode + 1
    if ($VersionName) { $NewVName = $VersionName } else { $NewVName = Step-VersionName $CurVName }
    if ($Interactive -and -not $VersionName) { $NewVName = Read-WithDefault "Version name" $NewVName }
}

Write-Host ""
Write-Host "New configuration" -ForegroundColor Cyan
Write-Host "  URL          : $Url"
Write-Host "  Scope        : $Scope"
Write-Host "  App name     : $Name"
Write-Host "  Version      : $NewVName (code $NewCode)"
Write-Host "  Build        : $Build"
Write-Host ""

if (-not $Yes) {
    $confirm = Read-WithDefault "Save and continue? (y/n)" "y"
    if ($confirm -notmatch '^[Yy]') { Stop-Kds "Cancelled - nothing changed." }
}

Set-Prop "KDS_URL" $Url
Set-Prop "KDS_SCOPE" $Scope
Set-Prop "APP_NAME" $Name
Set-Prop "VERSION_CODE" "$NewCode"
Set-Prop "VERSION_NAME" $NewVName
Write-Ok "kds.properties updated"

if ($Build -eq "none") {
    Write-Host ""
    Write-Host "Next: commit and push to let GitHub Actions build the APK:"
    Write-Host "  git commit -am `"KDS: $Url, version $NewVName`"; git push"
    exit 0
}

# ---------------------------------------------------------------- build

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Stop-Kds "Java (JDK 17+) not found. Install it, or use -Build none and let GitHub Actions build."
}

$localProps = Join-Path $Root "local.properties"
$hasSdkDir = (Test-Path $localProps) -and (Select-String -Path $localProps -Pattern '^sdk\.dir=' -Quiet)
if (-not $hasSdkDir) {
    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
    if (-not $sdk -and $env:LOCALAPPDATA) {
        $guess = Join-Path $env:LOCALAPPDATA "Android\Sdk"
        if (Test-Path $guess) { $sdk = $guess }
    }
    if (-not $sdk -or -not (Test-Path $sdk)) {
        Stop-Kds ("Android SDK not found. Install Android Studio (or the SDK), then set ANDROID_HOME`n" +
            "   or create local.properties with: sdk.dir=C:/Users/you/AppData/Local/Android/Sdk (forward slashes)`n" +
            "   Or run with -Build none, push, and download the APK from GitHub Actions.")
    }
    $env:ANDROID_HOME = $sdk
}

$gradlew = Join-Path $Root "gradlew.bat"
$dist = Join-Path $Root "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$safeName = "TapTill-KDS-$NewVName"

function Invoke-Gradle([string]$Task) {
    Push-Location $Root
    try {
        & $gradlew --quiet $Task
        if ($LASTEXITCODE -ne 0) { Stop-Kds "Gradle build failed ($Task). See the messages above." }
    } finally { Pop-Location }
}

if ($Build -eq "debug") {
    Write-Host "Building debug APK..." -ForegroundColor Cyan
    Invoke-Gradle "assembleDebug"
    $out = Join-Path $dist "$safeName-debug.apk"
    Copy-Item (Join-Path $Root "app\build\outputs\apk\debug\TapTill-KDS-debug.apk") $out -Force
    Write-Ok "Debug APK: $out"
    Write-Warn "Debug APKs are for testing only and cannot update a release install."
    exit 0
}

$signed = $false
if ($Keystore) {
    if (-not (Test-Path $Keystore)) { Stop-Kds "Keystore not found: $Keystore" }
    $env:KDS_KEYSTORE_PATH = (Resolve-Path $Keystore).Path
    if (-not $env:KDS_KEY_ALIAS) { $env:KDS_KEY_ALIAS = "taptill-kds" }
    if (-not $env:KDS_KEYSTORE_PASSWORD) {
        $secure = Read-Host "Keystore password" -AsSecureString
        $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        try { $env:KDS_KEYSTORE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
    }
    if (-not $env:KDS_KEY_PASSWORD) { $env:KDS_KEY_PASSWORD = $env:KDS_KEYSTORE_PASSWORD }
    Write-Host "Building signed release APK..." -ForegroundColor Cyan
    Invoke-Gradle "assembleRelease"
    $out = Join-Path $dist "$safeName-release.apk"
    Copy-Item (Join-Path $Root "app\build\outputs\apk\release\TapTill-KDS-release.apk") $out -Force
    Write-Ok "Signed release APK: $out"
    $signed = $true
} else {
    Write-Host "Building release APK (unsigned - no keystore given)..." -ForegroundColor Cyan
    Invoke-Gradle "assembleRelease"
    $out = Join-Path $dist "$safeName-release-unsigned.apk"
    Copy-Item (Join-Path $Root "app\build\outputs\apk\release\TapTill-KDS-release-unsigned.apk") $out -Force
    Write-Ok "Unsigned release APK: $out"
    Write-Warn "Re-run with -Keystore C:\path\to\taptill-kds-release.jks to get a signed APK you can install."
}

$sum = Get-Sha256 $out
Write-Host "  SHA-256: $sum"

if ($SaveRelease) {
    if (-not $signed) {
        Write-Warn "-SaveRelease skipped: only signed releases are saved to releases\"
    } else {
        $releases = Join-Path $Root "releases"
        New-Item -ItemType Directory -Force -Path $releases | Out-Null
        Copy-Item $out $releases -Force
        $leaf = Split-Path -Leaf $out
        [System.IO.File]::AppendAllText((Join-Path $releases "SHA256SUMS"), "$sum  $leaf`n", $Utf8NoBom)
        Write-Ok "Saved to releases\$leaf"
    }
}

Write-Host ""
Write-Host "Install / update on a TV:" -ForegroundColor Cyan
Write-Host "  adb connect <tv-ip-address>"
Write-Host "  adb install -r `"$out`""
Write-Host ""
Write-Host "Don't forget to commit kds.properties:"
Write-Host "  git commit -am `"KDS: $Url, version $NewVName`"; git push"
