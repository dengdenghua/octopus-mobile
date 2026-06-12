$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
cd "f:\新建文件夹\octopus-mobile"
$out = & .\gradlew.bat :app:compileDebugUnitTestKotlin --console=plain 2>&1
$out | Select-String -Pattern "^e: file:///" | ForEach-Object { $_.ToString() }
Write-Host "---"
Write-Host "Total errors: $(($out | Select-String -Pattern '^e: file:///').Count)"
