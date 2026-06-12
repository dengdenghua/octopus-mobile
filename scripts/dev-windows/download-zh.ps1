$url = 'https://plugins.jetbrains.com/files/13710/448234/zh.233.196.zip'
$dest = Join-Path $env:TEMP 'zh.233.196.zip'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
try {
    Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing -ErrorAction Stop
    $size = (Get-Item $dest).Length
    Write-Host "Downloaded: $dest ($size bytes)"
} catch {
    Write-Host "Failed: $_"
    exit 1
}
