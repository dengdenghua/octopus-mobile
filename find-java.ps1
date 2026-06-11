$javaPaths = @(
    "$env:LOCALAPPDATA\Programs\Android Studio\jbr\bin\java.exe",
    "C:\Program Files\Android\Android Studio\jbr\bin\java.exe",
    "$env:ProgramFiles\Eclipse Adoptium\jdk-17.*",
    "$env:ProgramFiles\Java\jdk-17*",
    "$env:ProgramFiles\Java\jdk-21*",
    "C:\Program Files\Java\jdk-17*",
    "C:\Program Files\Java\jdk-21*"
)
$found = @()
# 1. Android Studio bundled JBR
$asJbr = "C:\Program Files\Android\Android Studio\jbr"
if (Test-Path "$asJbr\bin\java.exe") {
    $found += $asJbr
    Write-Host "FOUND (AS bundled): $asJbr"
}
# 2. Local Android Studio AppData
$localAs = "$env:LOCALAPPDATA\Programs\Android Studio\jbr"
if (Test-Path "$localAs\bin\java.exe") {
    $found += $localAs
    Write-Host "FOUND (AS user): $localAs"
}
# 3. Search common locations
$results = Get-ChildItem -Path "C:\Program Files","C:\Program Files (x86)","$env:LOCALAPPDATA","$env:USERPROFILE" -Filter "java.exe" -Recurse -ErrorAction SilentlyContinue -Depth 6 |
    Where-Object { $_.FullName -notmatch '\\jre\\' -and $_.FullName -notmatch 'common\\' } |
    Select-Object -First 10
$results | ForEach-Object { Write-Host "CANDIDATE: $($_.FullName)" }
