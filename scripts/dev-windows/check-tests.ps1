$dir = 'f:\新建文件夹\octopus-mobile\app\build\test-results\testDebugUnitTest'
if (Test-Path $dir) {
    Get-ChildItem $dir -Filter '*.xml' | Select-Object Name
} else {
    Write-Host "Dir not found"
}
Write-Host '---'
Get-Content 'f:\新建文件夹\octopus-mobile\test-run.log' -Tail 8
