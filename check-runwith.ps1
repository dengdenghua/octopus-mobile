$dir = 'f:\新建文件夹\octopus-mobile\app\src\test\java\com\apk\claw\android'
$files = Get-ChildItem $dir -Filter '*.kt' -Recurse
$results = @()
foreach ($f in $files) {
    $firstLines = Get-Content $f.FullName -TotalCount 50
    $hasRunWith = $firstLines | Where-Object { $_ -match '^@RunWith' } | Select-Object -First 1
    if (-not $hasRunWith) {
        $imports = $firstLines | Where-Object { $_ -match '^import ' } | Select-Object -First 30
        $results += [PSCustomObject]@{
            Name = $f.Name
            HasApp = ($imports | Where-Object { $_ -match 'Application|androidx.test' }) -ne $null
            HasAndroid = ($imports | Where-Object { $_ -match '^import android\.' }) -ne $null
        }
    }
}
$results | Format-Table -AutoSize
