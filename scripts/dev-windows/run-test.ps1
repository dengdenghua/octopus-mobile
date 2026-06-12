$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
cd "f:\新建文件夹\octopus-mobile"

# 把整个 test 树全移走，只留 ConnectionStateMachineTest
$testRoot = "app\src\test\java"
$allKts = Get-ChildItem -Path $testRoot -Recurse -Filter "*.kt"
$moved = @()
foreach ($f in $allKts) {
    if ($f.Name -ne "ConnectionStateMachineTest.kt") {
        $dest = $f.FullName + ".disabled"
        Move-Item $f.FullName $dest -Force
        $moved += $f.FullName
    }
}
Write-Host ("Disabled {0} test files" -f $moved.Count)
try {
    & .\gradlew.bat :app:testDebugUnitTest --tests "com.apk.claw.android.octopus_mobile.ConnectionStateMachineTest" --console=plain 2>&1 | Select-Object -Last 30
    $ec = $LASTEXITCODE
    Write-Host "Gradle exit code: $ec"
} finally {
    foreach ($f in $moved) {
        $bak = $f + ".disabled"
        if (Test-Path $bak) { Move-Item $bak $f -Force }
    }
    Write-Host "Restored all disabled test files"
}
exit $ec
