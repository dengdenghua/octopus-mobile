Set-Location "f:\新建文件夹\octopus-mobile"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
& .\gradlew.bat compileDebugUnitTestKotlin 2>&1 | Tee-Object -FilePath "f:\新建文件夹\octopus-mobile\test-build.log" | Out-Null
Write-Host "=== ERRORS (first 200) ==="
$errors = Select-String -Path "f:\新建文件夹\octopus-mobile\test-build.log" -Pattern "^e: file" | Select-Object -First 200
$errors | ForEach-Object { $_.ToString() }
Write-Host "=== COUNT ==="
$count = (Select-String -Path "f:\新建文件夹\octopus-mobile\test-build.log" -Pattern "^e: file" | Measure-Object).Count
Write-Host "Total errors: $count"
Write-Host "=== LAST LINES ==="
Get-Content "f:\新建文件夹\octopus-mobile\test-build.log" -Tail 10
