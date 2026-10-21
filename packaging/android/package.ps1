<#
.SYNOPSIS
  Build, package and sign EffectCraft for Android (arm64-v8a APK).

.DESCRIPTION
  Produces, in $env:DIST (default: dist/release):
    effectcraft-<version>-android-arm64.apk

  Compiles the Rust NativeActivity shared library (libeffectcraft.so) for aarch64-linux-android
  with Vulkan GPU acceleration and Android storage/media permissions, compiles
  EffectCraftActivity.java into classes.dex, packages resources and icons with aapt2, aligns
  with zipalign, and signs with apksigner.

.EXAMPLE
  pwsh -NoProfile -File packaging/android/package.ps1
  pwsh -NoProfile -File packaging/android/package.ps1 -SkipBuild
#>
param(
  [switch] $SkipBuild
)
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

function Invoke-Native([string] $What, [scriptblock] $Block) {
  Write-Output "==> $What"
  & $Block
  if ($LASTEXITCODE -ne 0) { throw "$What failed with exit code $LASTEXITCODE" }
}

# Read [workspace.package] version from root Cargo.toml.
$Version = $env:EFFECTCRAFT_VERSION
if (-not $Version) {
  $inPkg = $false
  foreach ($line in Get-Content (Join-Path $Root 'Cargo.toml')) {
    if ($line -match '^\s*\[') { $inPkg = ($line.Trim() -eq '[workspace.package]'); continue }
    if ($inPkg -and $line -match '^\s*version\s*=\s*"([^"]+)"') { $Version = $Matches[1]; break }
  }
}
if (-not $Version) { throw 'could not read [workspace.package] version from Cargo.toml' }

$Target = 'aarch64-linux-android'
$Dist = if ($env:DIST) { $env:DIST } else { Join-Path $Root 'dist\release' }
$TargetDir = if ($env:CARGO_TARGET_DIR) { $env:CARGO_TARGET_DIR } else { Join-Path $Root 'target' }
New-Item -ItemType Directory -Force -Path $Dist | Out-Null

# Locate Android SDK, NDK, build-tools, platform android.jar, and JDK.
$Sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not (Test-Path $Sdk)) { throw "Android SDK not found at $Sdk (set ANDROID_HOME)" }

$Ndk = if ($env:ANDROID_NDK_HOME -and (Test-Path $env:ANDROID_NDK_HOME)) {
  $env:ANDROID_NDK_HOME
} else {
  Get-ChildItem (Join-Path $Sdk 'ndk') -Directory | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $Ndk) { throw "Android NDK not found under $Sdk\ndk" }

$BuildTools = Get-ChildItem (Join-Path $Sdk 'build-tools') -Directory | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
if (-not $BuildTools) { throw "Android build-tools not found under $Sdk\build-tools" }

$PlatformDir = Get-ChildItem (Join-Path $Sdk 'platforms') -Directory | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
$AndroidJar = if ($PlatformDir) { Join-Path $PlatformDir 'android.jar' } else { $null }
if (-not $AndroidJar -or -not (Test-Path $AndroidJar)) { throw "android.jar not found under $Sdk\platforms" }

$JavaHome = $env:JAVA_HOME
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\javac.exe'))) {
  $JdkCandidate = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
  if ($JdkCandidate) { $JavaHome = $JdkCandidate }
}
if ($JavaHome -and (Test-Path (Join-Path $JavaHome 'bin'))) {
  $env:JAVA_HOME = $JavaHome
  $env:PATH = "$(Join-Path $JavaHome 'bin');$env:PATH"
}

$Javac = if ($JavaHome) { Join-Path $JavaHome 'bin\javac.exe' } else { 'javac' }
$Keytool = if ($JavaHome) { Join-Path $JavaHome 'bin\keytool.exe' } else { 'keytool' }
$Aapt2 = Join-Path $BuildTools 'aapt2.exe'
$D8 = Join-Path $BuildTools 'd8.bat'
$ZipAlign = Join-Path $BuildTools 'zipalign.exe'
$ApkSigner = Join-Path $BuildTools 'apksigner.bat'

$LlvmBin = Join-Path $Ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin'
if (-not (Test-Path $LlvmBin)) { throw "NDK LLVM toolchain not found at $LlvmBin" }

$HostTools = Join-Path $TargetDir 'host-tools'
New-Item -ItemType Directory -Force -Path $HostTools | Out-Null
$RustupSelfContained = Join-Path $env:USERPROFILE '.rustup\toolchains\stable-x86_64-pc-windows-gnu\lib\rustlib\x86_64-pc-windows-gnu\bin\self-contained'
if (Test-Path $RustupSelfContained) {
  Copy-Item (Join-Path $RustupSelfContained 'dlltool.exe') (Join-Path $HostTools 'dlltool.exe') -Force
  Copy-Item (Join-Path $RustupSelfContained 'libwinpthread-1.dll') (Join-Path $HostTools 'libwinpthread-1.dll') -Force
  $env:PATH = "$HostTools;$LlvmBin;$RustupSelfContained;$env:PATH"
  $AsExe = Join-Path $HostTools 'as.exe'
  if (-not (Test-Path $AsExe)) {
    $AsWrapRs = Join-Path $HostTools 'as_wrap.rs'
    $ClangEscaped = (Join-Path $LlvmBin 'clang.exe') -replace '\\', '\\'
    Set-Content -Path $AsWrapRs -Value @"
fn main() {
    let mut cmd = std::process::Command::new("$ClangEscaped");
    cmd.args(["--target=x86_64-w64-mingw32", "-c", "-x", "assembler"]);
    let mut args = std::env::args().skip(1);
    while let Some(a) = args.next() {
        match a.as_str() {
            "--64" => {}
            "-o" => { if let Some(out) = args.next() { cmd.arg("-o").arg(out); } }
            other => { cmd.arg(other); }
        }
    }
    let status = cmd.status().unwrap();
    std::process::exit(status.code().unwrap_or(1));
}
"@
    rustc -O $AsWrapRs -o $AsExe
  }
} else {
  $env:PATH = "$LlvmBin;$env:PATH"
}

Write-Output "EffectCraft $Version for Android arm64 ($Target)"
Write-Output "  SDK:         $Sdk"
Write-Output "  NDK:         $Ndk"
Write-Output "  Build-Tools: $BuildTools"
Write-Output "  Platform:    $AndroidJar"

$ApkCargoManifest = Join-Path $PSScriptRoot 'apk\Cargo.toml'
$ApkCargoLock = Join-Path $PSScriptRoot 'apk\Cargo.lock'

if (-not $SkipBuild) {
  $env:ANDROID_NDK_HOME = $Ndk
  $env:ANDROID_NDK_ROOT = $Ndk
  $env:CC_aarch64_linux_android = Join-Path $LlvmBin 'aarch64-linux-android26-clang.cmd'
  $env:CXX_aarch64_linux_android = Join-Path $LlvmBin 'aarch64-linux-android26-clang++.cmd'
  $env:AR_aarch64_linux_android = Join-Path $LlvmBin 'llvm-ar.exe'
  $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = Join-Path $LlvmBin 'aarch64-linux-android26-clang.cmd'
  $env:CARGO_TARGET_DIR = $TargetDir

  Copy-Item (Join-Path $Root 'Cargo.lock') $ApkCargoLock -Force
  try {
    Invoke-Native "cargo build ($Target)" {
      cargo build --manifest-path $ApkCargoManifest --target $Target --release
    }
  } finally {
    Remove-Item -Force $ApkCargoLock -ErrorAction SilentlyContinue
  }
}

$SoPath = Join-Path $TargetDir "$Target\release\libeffectcraft.so"
if (-not (Test-Path $SoPath)) { throw "missing shared library $SoPath" }

# Verify ELF header (0x7F 'E' 'L' 'F', 64-bit little-endian, EM_AARCH64 = 0xB7).
$elfBytes = [System.IO.File]::ReadAllBytes($SoPath)
$machine = [BitConverter]::ToUInt16($elfBytes, 18)
if ($elfBytes[0] -ne 0x7F -or $elfBytes[1] -ne 0x45 -or $elfBytes[2] -ne 0x4C -or $elfBytes[3] -ne 0x46 -or $machine -ne 0xB7) {
  throw "libeffectcraft.so is not a valid AArch64 ELF shared library (machine=0x$('{0:X}' -f $machine))"
}
Write-Output "ok libeffectcraft.so: AArch64 ELF ($([Math]::Round($elfBytes.Length / 1MB, 2)) MB)"

$Stage = Join-Path $TargetDir 'android-package'
Remove-Item -Recurse -Force $Stage -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Stage | Out-Null

# Stage launcher icons from assets/app-icon/hicolor into Android mipmap directories inside target/.
$ResDir = Join-Path $Stage 'res'
$IconMap = @{
  'mipmap-mdpi'    = '48x48'
  'mipmap-xhdpi'   = '128x128'
  'mipmap-xxhdpi'  = '256x256'
  'mipmap-xxxhdpi' = '512x512'
}
foreach ($entry in $IconMap.GetEnumerator()) {
  $dir = Join-Path $ResDir $entry.Key
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  $src = Join-Path $Root "assets\app-icon\hicolor\$($entry.Value)\apps\ai.storyteller.effectcraft.png"
  Copy-Item $src (Join-Path $dir 'ic_launcher.png') -Force
}

$ResZip = Join-Path $Stage 'res.zip'
Invoke-Native 'aapt2 compile' {
  & $Aapt2 compile --dir $ResDir -o $ResZip
}

$GenJava = Join-Path $Stage 'gen'
New-Item -ItemType Directory -Force -Path $GenJava | Out-Null
$BaseApk = Join-Path $Stage 'base.apk'
$Manifest = Join-Path $PSScriptRoot 'AndroidManifest.xml'
Invoke-Native 'aapt2 link' {
  & $Aapt2 link -o $BaseApk -I $AndroidJar --manifest $Manifest --java $GenJava `
    --version-name $Version --auto-add-overlay $ResZip
}

# Compile EffectCraftActivity.java + generated R.java and convert to classes.dex.
$ClassesDir = Join-Path $Stage 'classes'
New-Item -ItemType Directory -Force -Path $ClassesDir | Out-Null
$ActivityJava = Join-Path $PSScriptRoot 'java\ai\storyteller\effectcraft\EffectCraftActivity.java'
$RJava = Join-Path $GenJava 'ai\storyteller\effectcraft\R.java'
Invoke-Native 'javac EffectCraftActivity.java' {
  & $Javac --release 11 -encoding UTF-8 -cp $AndroidJar -d $ClassesDir $ActivityJava $RJava
}

$DexDir = Join-Path $Stage 'dex'
New-Item -ItemType Directory -Force -Path $DexDir | Out-Null
$ClassFiles = Get-ChildItem (Join-Path $ClassesDir 'ai\storyteller\effectcraft') -Filter '*.class' | Select-Object -ExpandProperty FullName
Invoke-Native 'd8 classes.dex' {
  & $D8 --min-api 26 --lib $AndroidJar --output $DexDir $ClassFiles
}

# Assemble unaligned APK with classes.dex and lib/arm64-v8a/libeffectcraft.so.
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$UnalignedApk = Join-Path $Stage 'unaligned.apk'
Copy-Item $BaseApk $UnalignedApk -Force

$zip = [System.IO.Compression.ZipFile]::Open($UnalignedApk, [System.IO.Compression.ZipArchiveMode]::Update)
try {
  $dexFile = Join-Path $DexDir 'classes.dex'
  [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $dexFile, 'classes.dex', [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
  [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $SoPath, 'lib/arm64-v8a/libeffectcraft.so', [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null

  # Include libc++_shared.so if present and needed by native dependencies.
  $LibCppShared = Join-Path $Ndk 'toolchains\llvm\prebuilt\windows-x86_64\sysroot\usr\lib\aarch64-linux-android\libc++_shared.so'
  if (Test-Path $LibCppShared) {
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $LibCppShared, 'lib/arm64-v8a/libc++_shared.so', [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
  }
} finally {
  $zip.Dispose()
}

$AlignedApk = Join-Path $Stage 'aligned.apk'
Invoke-Native 'zipalign' {
  & $ZipAlign -f -p 4 $UnalignedApk $AlignedApk
}

# Sign the APK (uses ANDROID_KEYSTORE if configured, otherwise generates a local debug keystore in target/).
$Keystore = if ($env:ANDROID_KEYSTORE) { $env:ANDROID_KEYSTORE } else { Join-Path $Stage 'debug.keystore' }
$KeyAlias = if ($env:ANDROID_KEY_ALIAS) { $env:ANDROID_KEY_ALIAS } else { 'androiddebugkey' }
$KeyPass = if ($env:ANDROID_KEYSTORE_PASS) { $env:ANDROID_KEYSTORE_PASS } else { 'android' }
if (-not (Test-Path $Keystore)) {
  Invoke-Native 'keytool generate keystore' {
    & $Keytool -genkeypair -v -keystore $Keystore -storepass $KeyPass -alias $KeyAlias `
      -keypass $KeyPass -keyalg RSA -keysize 2048 -validity 10000 `
      -dname 'CN=EffectCraft Android,O=Storyteller,C=US'
  }
}

$FinalApk = Join-Path $Dist "effectcraft-$Version-android-arm64.apk"
Invoke-Native 'apksigner sign' {
  & $ApkSigner sign --ks $Keystore --ks-key-alias $KeyAlias --ks-pass "pass:$KeyPass" --key-pass "pass:$KeyPass" --out $FinalApk $AlignedApk
}
Invoke-Native 'apksigner verify' {
  & $ApkSigner verify --verbose $FinalApk
}

$apkSizeMb = [Math]::Round((Get-Item $FinalApk).Length / 1MB, 2)
Write-Output "==> Built and signed APK: $FinalApk ($apkSizeMb MB)"
