$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
cd "f:\新建文件夹\octopus-mobile"
$out = .\gradlew.bat -q --console=plain :app:dependencies --configuration debugCompileClasspath 2>&1
$out | Select-String -Pattern "kotlin-stdlib|kotlin-reflect|kotlin-compiler-embeddable" | Select-Object -First 10
Write-Host "---"
$out | Select-String -Pattern "kotlin-compose" | Select-Object -First 5
