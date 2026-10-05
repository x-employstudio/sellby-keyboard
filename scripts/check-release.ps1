# Release gate for Sellby Keyboard: inspects a built release APK (and optionally the AAB) and fails (exit 1)
# when something would get the upload rejected or the app broken. Run it after `gradlew assembleRelease`
# (and `bundleRelease`), before uploading anything to Google Play.
#
#   powershell -ExecutionPolicy Bypass -File scripts\check-release.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\check-release.ps1 -Apk path\to.apk -Aab path\to.aab
#   add -AllowDebugSigned to check a locally smoke-test build (assembleRelease -PlocalReleaseSign)
#
# ASCII only on purpose: Windows PowerShell 5.1 reads a script without BOM as ANSI.

param(
    [string]$Apk = "",
    [string]$Aab = "",
    [switch]$AllowDebugSigned
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }

# ---- the permission whitelist: anything else in the merged manifest fails the gate -------------------------
# Keep this in step with docs/RELEASE.md and the Data safety form in Play Console.
$AllowedPermissions = @(
    "android.permission.VIBRATE",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.READ_CONTACTS",           # asked once from the Invoice panel's contact button (declared on purpose, see the Data safety form)
    "com.sellby.keyboard.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    "com.android.vending.BILLING",                # merged in by the Play Billing library
    # The next two are merged in by the Play Billing library as well (its dependency
    # com.google.android.datatransport:transport-backend-cct, Google's own usage logging). INTERNET was approved
    # for the trial-gate server anyway (batch 6); now it is declared from the Billing release on, so the privacy
    # policy and the Data safety answers must be written with it in mind.
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE"
)

# classes that must survive R8 (proves the keep rules / consumer rules work)
$ExpectedPackages = @("androidx.room", "com.google.android.gms.auth.blockstore", "com.google.android.play.core.review", "com.android.billingclient.api", "kotlinx.serialization", "com.android.inputmethod.latin")

$failures = New-Object System.Collections.Generic.List[string]
$warnings = New-Object System.Collections.Generic.List[string]
function Fail($m) { $script:failures.Add($m); Write-Host "  FAIL  $m" -ForegroundColor Red }
function Warn($m) { $script:warnings.Add($m); Write-Host "  warn  $m" -ForegroundColor Yellow }
function Ok($m) { Write-Host "  ok    $m" -ForegroundColor Green }
function Section($m) { Write-Host ""; Write-Host "== $m" -ForegroundColor Cyan }

function Find-Tool($pattern, $roots) {
    foreach ($r in $roots) {
        if (Test-Path $r) {
            $hit = Get-ChildItem -Path $r -Recurse -Filter $pattern -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1
            if ($hit) { return $hit.FullName }
        }
    }
    return $null
}


# Runs a native tool, merging stderr into the result without tripping $ErrorActionPreference=Stop (Windows
# PowerShell 5.1 turns any stderr line of a redirected native command into a terminating error), and dropping
# the harmless JVM "WARNING:" noise that the SDK's Java tools print.
function Run-Tool($exe, [string[]]$toolArgs) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $lines = @(& $exe @toolArgs 2>&1 | ForEach-Object { $_.ToString() })
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $old }
    $clean = @($lines | Where-Object { $_ -and $_ -notmatch "^WARNING:" -and $_ -notmatch "^\s*\+ " -and $_ -notmatch "CategoryInfo|FullyQualifiedErrorId|^At .*char:" })
    return [pscustomobject]@{ Lines = $clean; Text = ($clean -join "`n"); Code = $code }
}

$zipalign = Find-Tool "zipalign.exe" @((Join-Path $sdk "build-tools"))
$apksigner = Find-Tool "apksigner.bat" @((Join-Path $sdk "build-tools"))
$apkanalyzer = Join-Path $sdk "cmdline-tools\latest\bin\apkanalyzer.bat"
$readelf = Find-Tool "llvm-readelf.exe" @((Join-Path $sdk "ndk"))

if (-not $Apk) {
    $dir = Join-Path $repo "app\build\outputs\apk\release"
    $latest = Get-ChildItem -Path $dir -Filter "*.apk" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($latest) { $Apk = $latest.FullName }
}
if (-not $Apk -or -not (Test-Path $Apk)) { Write-Host "No release APK found. Run: gradlew assembleRelease" -ForegroundColor Red; exit 1 }
Write-Host "APK: $Apk"

# ---- 1. identity -------------------------------------------------------------------------------------------
Section "Identity"
if (-not (Test-Path $apkanalyzer)) { Fail "apkanalyzer not found at $apkanalyzer (install Android SDK cmdline-tools)" }
else {
    $appId = (Run-Tool $apkanalyzer @('manifest','application-id',$Apk)).Lines[-1].Trim()
    $verName = (Run-Tool $apkanalyzer @('manifest','version-name',$Apk)).Lines[-1].Trim()
    $verCode = (Run-Tool $apkanalyzer @('manifest','version-code',$Apk)).Lines[-1].Trim()
    $target = (Run-Tool $apkanalyzer @('manifest','target-sdk',$Apk)).Lines[-1].Trim()
    $minSdk = (Run-Tool $apkanalyzer @('manifest','min-sdk',$Apk)).Lines[-1].Trim()
    $debuggable = (Run-Tool $apkanalyzer @('manifest','debuggable',$Apk)).Lines[-1].Trim()
    Write-Host "  $appId  versionName=$verName  versionCode=$verCode  minSdk=$minSdk  targetSdk=$target  debuggable=$debuggable"
    if ($appId -ne "com.sellby.keyboard") { Fail "applicationId is '$appId', expected com.sellby.keyboard" } else { Ok "applicationId" }
    if ($debuggable -eq "true") { Fail "the APK is debuggable" } else { Ok "not debuggable" }
    if ([int]$target -lt 36) { Fail "targetSdk $target is below Play's required 36" } else { Ok "targetSdk $target" }
    if ([int]$verCode -le 4101) { Fail "versionCode $verCode must be above HeliBoard's inherited 4101" } else { Ok "versionCode $verCode" }
}

# ---- 2. permissions ----------------------------------------------------------------------------------------
Section "Permissions (merged manifest)"
if (Test-Path $apkanalyzer) {
    $perms = @((Run-Tool $apkanalyzer @('manifest','permissions',$Apk)).Lines | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    foreach ($p in $perms) {
        if ($AllowedPermissions -contains $p) { Ok $p } else { Fail "unexpected permission: $p" }
    }
}

# ---- 2b. public links ----------------------------------------------------------------------------------------
# The GPL requires offering the source of what is shipped, and Google Play requires a working privacy policy link:
# a release must not go out while the addresses in SellbyLinks.kt are still empty or placeholders.
Section "Public links (SellbyLinks.kt)"
$linksFile = Join-Path $repo "app\src\main\java\helium314\keyboard\sellby\companion\SellbyLinks.kt"
if (-not (Test-Path $linksFile)) {
    Fail "SellbyLinks.kt not found"
} else {
    $linksText = Get-Content -Raw $linksFile
    foreach ($name in @("SOURCE_URL", "SITE_URL")) {
        $m = [regex]::Match($linksText, 'const val ' + $name + ' = "([^"]*)"')
        $value = if ($m.Success) { $m.Groups[1].Value.Trim() } else { "" }
        if ($value -eq "" -or $value -like "*{{*" -or $value -notlike "https://*") {
            if ($AllowDebugSigned) { Warn "$name is not set (allowed for a local smoke test; a release must have it)" }
            else { Fail "$name is not set to an https:// address in SellbyLinks.kt" }
        } else { Ok "$name = $value" }
    }
}

# ---- 3. signature ------------------------------------------------------------------------------------------
Section "Signature"
if ($apksigner) {
    $sigRun = Run-Tool $apksigner @('verify','--print-certs',$Apk)
    $sig = $sigRun.Text
    if ($sig -match "Android Debug") {
        if ($AllowDebugSigned) { Warn "signed with the Android Debug key (allowed for a local smoke test; NEVER upload this)" }
        else { Fail "signed with the Android Debug key - sign with the upload key (keystore.properties) before uploading" }
    } elseif ($sigRun.Code -ne 0 -or $sig -match "DOES NOT VERIFY") {
        Fail "the APK is unsigned or its signature does not verify"
    } else { Ok "signed with a non-debug key" }
} else { Warn "apksigner not found, signature not checked" }

# ---- 4. alignment ------------------------------------------------------------------------------------------
Section "Alignment (zip + 16 KB page size)"
if ($zipalign) {
    $za = Run-Tool $zipalign @('-c','-P','16','-v','4',$Apk)
    if ($za.Code -eq 0) { Ok "zipalign -c -P 16 passes" } else { Fail "zipalign -P 16 reports misalignment" }
} else { Warn "zipalign not found" }

if ($readelf) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $tmp = Join-Path $env:TEMP ("sellby-elf-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $tmp | Out-Null
    try {
        $zip = [System.IO.Compression.ZipFile]::OpenRead($Apk)
        $libs = @($zip.Entries | Where-Object { $_.FullName -like "lib/*/*.so" })
        foreach ($e in $libs) {
            $abi = $e.FullName.Split("/")[1]
            $dest = Join-Path $tmp ($abi + "_" + $e.Name)
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $dest, $true)
            if ($abi -eq "arm64-v8a" -or $abi -eq "x86_64") {
                $loads = (Run-Tool $readelf @('-lW',$dest)).Lines | Where-Object { $_ -match "^\s*LOAD" }
                $bad = @()
                foreach ($l in $loads) {
                    $align = ($l -split "\s+" | Where-Object { $_ })[-1]
                    if ([Convert]::ToInt64($align, 16) -lt 0x4000) { $bad += $align }
                }
                if ($bad.Count -gt 0) { Fail "$($e.FullName): LOAD segment alignment below 16 KB ($($bad -join ','))" } else { Ok "$($e.FullName): 16 KB aligned" }
            }
        }
        $zip.Dispose()
        if ($libs.Count -eq 0) { Fail "no native libraries found in the APK" }
    } finally { Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue }
} else { Warn "llvm-readelf not found (NDK), ELF alignment not checked" }

# ---- 5. R8 survivors ---------------------------------------------------------------------------------------
Section "Classes that must survive R8"
if (Test-Path $apkanalyzer) {
    $pkgs = (Run-Tool $apkanalyzer @('dex','packages','--defined-only',$Apk)).Text
    foreach ($p in $ExpectedPackages) {
        if ($pkgs -match [regex]::Escape($p)) { Ok $p } else { Fail "package missing after shrinking: $p" }
    }
}

# ---- 6. size -----------------------------------------------------------------------------------------------
Section "Size"
$mb = [math]::Round((Get-Item $Apk).Length / 1MB, 1)
Write-Host "  APK size: $mb MB"
if ($mb -gt 60) { Warn "APK above 60 MB" }

# ---- 7. AAB (optional) -------------------------------------------------------------------------------------
if (-not $Aab) {
    $dir = Join-Path $repo "app\build\outputs\bundle\release"
    $latestAab = Get-ChildItem -Path $dir -Filter "*.aab" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($latestAab) { $Aab = $latestAab.FullName }
}
if ($Aab -and (Test-Path $Aab)) {
    Section "AAB: $Aab"
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($Aab)
    $names = @($zip.Entries | ForEach-Object { $_.FullName })
    $zip.Dispose()
    if ($names -contains "base/lib/arm64-v8a/libjni_latinime.so") { Ok "contains arm64-v8a native library" } else { Fail "arm64-v8a libjni_latinime.so missing from the bundle" }
    if (@($names | Where-Object { $_ -like "BUNDLE-METADATA/com.android.tools.build.debugsymbols/*" }).Count -gt 0) { Ok "native debug symbols included" } else { Fail "no native debug symbols in the bundle (ndk.debugSymbolLevel)" }
    if (@($names | Where-Object { $_ -match "\.(jks|keystore|properties)$" -and $_ -match "keystore" }).Count -gt 0) { Fail "a keystore file is inside the bundle" } else { Ok "no keystore inside the bundle" }
    $mbAab = [math]::Round((Get-Item $Aab).Length / 1MB, 1)
    Write-Host "  AAB size: $mbAab MB"
} else { Warn "no AAB checked (run gradlew bundleRelease, or pass -Aab)" }

# ---- summary -----------------------------------------------------------------------------------------------
Write-Host ""
if ($failures.Count -gt 0) {
    Write-Host "RELEASE CHECK FAILED: $($failures.Count) problem(s), $($warnings.Count) warning(s)" -ForegroundColor Red
    exit 1
}
Write-Host "RELEASE CHECK PASSED ($($warnings.Count) warning(s))" -ForegroundColor Green
exit 0
