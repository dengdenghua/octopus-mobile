Get-ChildItem 'f:\新建文件夹\octopus-mobile\app\src\test\java' -Recurse -Filter '*.kt' | ForEach-Object { Write-Host ('== ' + $_.Name) }
