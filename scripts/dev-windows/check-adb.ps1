$sdkPaths = @(
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    "$env:ANDROID_HOME\platform-tools\adb.exe",
    "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe",
    "C:\Android\Sdk\platform-tools\adb.exe",
    "C:\Users\12035\AppData\Local\Android\Sdk\platform-tools\adb.exe"
)
$adb = $null
foreach ($p in $sdkPaths) {
    if (Test-Path $p) { $adb = $p; break }
}
if (-not $adb) {
    Write-Host "Not found in standard paths. Searching..."
    $adb = Get-ChildItem -Path $env:USERPROFILE,$env:LOCALAPPDATA,"C:\","D:\" -Filter "adb.exe" -Recurse -ErrorAction SilentlyContinue -Depth 5 | Select-Object -First 1
    if ($adb) { $adb = $adb.FullName }
}
Write-Host "ADB: $adb"
if ($adb) {
    & $adb devices
    Write-Host "---"
    & $adb shell getprop ro.build.version.release 2>&1
    Write-Host "---"
    & $adb shell getprop ro.product.model 2>&1
}
