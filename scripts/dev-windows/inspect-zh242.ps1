$zip = 'C:\Users\12035\AppData\Local\Temp\zh.242.152.zip'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$entries = [System.IO.Compression.ZipFile]::OpenRead($zip).Entries
$entries | Where-Object { $_.FullName -match "META-INF|plugin.xml|MANIFEST" } | ForEach-Object { Write-Host $_.FullName }
