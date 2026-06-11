Set-Location "f:\新建文件夹\octopus-mobile"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
& .\gradlew.bat testDebugUnitTest 2>&1 | Tee-Object -FilePath "f:\新建文件夹\octopus-mobile\test-run.log" | Out-Null
Write-Host "=== TAIL ==="
Get-Content "f:\新建文件夹\octopus-mobile\test-run.log" -Tail 80
