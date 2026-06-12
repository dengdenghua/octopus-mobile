$logPath = "f:\新建文件夹\octopus-mobile\test-build.log"
$content = Get-Content $logPath -Raw
# Extract error blocks (each error is multi-line)
$lines = Get-Content $logPath
$out = @()
$inError = $false
$block = @()
for ($i = 0; $i -lt $lines.Count; $i++) {
    $line = $lines[$i]
    if ($line -match '^e: file:') {
        if ($block.Count -gt 0) {
            $out += $block
            $out += ""
        }
        $block = @($line)
    } elseif ($block.Count -gt 0 -and ($line -match '^\s*\^' -or $line -match 'error:' -or ($line -notmatch '^\s*$' -and $line -notmatch '^[A-Z][a-z]+ [a-z]+'))) {
        $block += $line
    } elseif ($block.Count -gt 0 -and $line -match '^\s*$') {
        $out += $block
        $out += ""
        $block = @()
    }
}
if ($block.Count -gt 0) { $out += $block }
$out | ForEach-Object { Write-Host $_ }
