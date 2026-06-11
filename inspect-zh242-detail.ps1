Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = 'C:\Users\12035\AppData\Local\Temp\zh.242.152.zip'
$entries = [System.IO.Compression.ZipFile]::OpenRead($zip).Entries
Write-Host "All directories:"
$entries | Where-Object { $_.FullName.EndsWith('/') } | Select-Object -First 20 FullName | Format-Table -AutoSize
Write-Host "---"
Write-Host "Key files:"
$entries | Where-Object { $_.FullName -match 'META-INF|plugin\.xml|module\.xml|product' } | ForEach-Object { Write-Host $_.FullName }
