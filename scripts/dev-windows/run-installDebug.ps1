$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Write-Host "JAVA_HOME = $env:JAVA_HOME"
& java -version
Write-Host "---"
cd "f:\新建文件夹\octopus-mobile"
& .\gradlew.bat installDebug --console=plain
$ec = $LASTEXITCODE
Write-Host "Gradle exit code: $ec"
exit $ec
