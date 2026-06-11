$paths = @(
    "$env:LOCALAPPDATA\Programs\Android Studio\bin\studio64.exe",
    "$env:PROGRAMFILES\Android\Android Studio\bin\studio64.exe",
    "${env:ProgramFiles(x86)}\Android\Android Studio\bin\studio64.exe",
    "C:\Program Files\Android\Android Studio\bin\studio64.exe",
    "D:\Android\Android Studio\bin\studio64.exe",
    "E:\Android\Android Studio\bin\studio64.exe"
)
$found = $null
foreach ($p in $paths) {
    if (Test-Path $p) {
        Write-Host "FOUND: $p"
        $found = $p
        break
    }
}
if (-not $found) {
    Write-Host "Android Studio not in standard locations. Searching common dirs..."
    $candidates = Get-ChildItem -Path "C:\","D:\","E:\" -Filter "studio64.exe" -Recurse -ErrorAction SilentlyContinue -Depth 4 | Select-Object -First 5
    $candidates | ForEach-Object { Write-Host $_.FullName }
}
