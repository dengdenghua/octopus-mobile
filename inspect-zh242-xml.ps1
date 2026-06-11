Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = 'C:\Users\12035\AppData\Local\Temp\zh.242.152.zip'
$entries = [System.IO.Compression.ZipFile]::OpenRead($zip).Entries
Write-Host "All files in lib/:"
$entries | Where-Object { $_.FullName -match 'lib/' -and -not $_.FullName.EndsWith('/') } | Select-Object -First 30 FullName, Length | Format-Table -AutoSize
Write-Host "---"
Write-Host "All XML files:"
$entries | Where-Object { $_.FullName -match '\.xml$' } | ForEach-Object { Write-Host $_.FullName }
Write-Host "---"
Write-Host "All JAR files:"
$entries | Where-Object { $_.FullName -match '\.jar$' } | ForEach-Object { Write-Host $_.FullName }
