$path = 'f:\新建文件夹\octopus-mobile\app\src\test\java\com\apk\claw\android\octopus_mobile\SkillManifestTest.kt'
$content = [System.IO.File]::ReadAllText($path)
# Find all /* and */ occurrences
$openMatches = [regex]::Matches($content, '/\*')
$closeMatches = [regex]::Matches($content, '\*/')
Write-Host "Open /* count: $($openMatches.Count)"
Write-Host "Close */ count: $($closeMatches.Count)"
# List all positions
$line = 1
$col = 0
$events = @()
for ($i = 0; $i -lt $content.Length; $i++) {
    $col++
    if ($content[$i] -eq "`n") { $line++; $col = 0 }
    if ($i -ge 1 -and $content[$i-1] -eq '/' -and $content[$i] -eq '*') {
        $events += [PSCustomObject]@{Idx=$i; Line=$line; Col=$col; Type='OPEN'}
    }
    if ($i -ge 1 -and $content[$i-1] -eq '*' -and $content[$i] -eq '/') {
        $events += [PSCustomObject]@{Idx=$i; Line=$line; Col=$col; Type='CLOSE'}
    }
}
$events | ForEach-Object { Write-Host ("  L{0,3}C{1,3}  {2}" -f $_.Line, $_.Col, $_.Type) }
