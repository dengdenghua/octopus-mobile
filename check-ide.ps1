$processes = @('studio64.exe','idea64.exe','pycharm64.exe','webstorm64.exe','goland64.exe','clion64.exe','phpstorm64.exe','rubymine64.exe','rider64.exe','datagrip64.exe')
foreach ($p in $processes) {
    $r = Get-Process -Name ($p -replace '\.exe$','') -ErrorAction SilentlyContinue
    if ($r) {
        Write-Host "[$p] running, PID=$($r.Id)"
    }
}
Write-Host "---"
Write-Host "All JetBrains-related processes:"
Get-Process | Where-Object { $_.ProcessName -match 'studio|idea|jetbrains' } | Select-Object ProcessName, Id, MainWindowTitle | Format-Table -AutoSize
