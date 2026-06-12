$url = 'https://plugins.jetbrains.com/files/13710/557305/zh.242.152.zip'
$dest = Join-Path $env:TEMP 'zh.242.152.zip'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
try {
    Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing -ErrorAction Stop
    $size = (Get-Item $dest).Length
    Write-Host "Downloaded: $dest ($size bytes)"
} catch {
    Write-Host "Failed: $_"
    exit 1
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$entries = [System.IO.Compression.ZipFile]::OpenRead($dest).Entries
Write-Host "Total entries: $($entries.Count)"
$entries | Select-Object -First 6 FullName | Format-Table -AutoSize
