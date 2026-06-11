Get-ChildItem 'C:\' -Directory -ErrorAction SilentlyContinue | Select-Object FullName
Write-Host "---"
Get-ChildItem 'C:\Program Files\Android' -Recurse -Directory -ErrorAction SilentlyContinue -Depth 4 | Where-Object { $_.Name -match 'jbr|jdk|java' } | Select-Object FullName
Write-Host "---"
$javaPath = (Get-Command java -ErrorAction SilentlyContinue).Source
Write-Host "Java in PATH: $javaPath"
