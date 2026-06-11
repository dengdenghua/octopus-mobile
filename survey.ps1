$root = 'f:\新建文件夹\octopus-mobile\app\src\main\java\com\apk\claw\android'
Get-ChildItem -Path $root -Recurse -File -Include '*.kt','*.java' |
    Where-Object { $_.Name -ne 'BuildConfig.kt' } |
    Group-Object { ($_.FullName -replace [regex]::Escape($root),'').Split('\')[1] } |
    Select-Object @{n='Package';e={$_.Name}}, Count |
    Sort-Object Count -Descending | Format-Table -AutoSize
Write-Host "---"
Write-Host "Total files:"
(Get-ChildItem -Path $root -Recurse -File -Include '*.kt','*.java' | Where-Object { $_.Name -ne 'BuildConfig.kt' }).Count
Write-Host "---"
Write-Host "Large files (>300 lines):"
Get-ChildItem -Path $root -Recurse -File -Include '*.kt','*.java' |
    Where-Object { $_.Name -ne 'BuildConfig.kt' } |
    ForEach-Object { $lines = (Get-Content $_.FullName | Measure-Object -Line).Lines; [PSCustomObject]@{ File = $_.FullName.Substring($root.Length+1); Lines = $lines } } |
    Where-Object { $_.Lines -gt 300 } |
    Sort-Object Lines -Descending | Format-Table -AutoSize
