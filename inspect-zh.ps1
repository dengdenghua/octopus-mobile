$zip = 'C:\Users\12035\AppData\Local\Temp\zh.233.196.zip'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$entries = [System.IO.Compression.ZipFile]::OpenRead($zip).Entries
Write-Host "Total entries: $($entries.Count)"
$entries | Select-Object -First 8 FullName, Length | Format-Table -AutoSize
