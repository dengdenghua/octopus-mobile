$dir = 'f:\新建文件夹\octopus-mobile\app\build\test-results\testDebugUnitTest'
$files = Get-ChildItem $dir -Filter '*.xml'
$results = @()
foreach ($f in $files) {
    [xml]$xml = Get-Content $f.FullName
    $suite = $xml.testsuite
    $results += [PSCustomObject]@{
        Name = $suite.name
        Tests = [int]$suite.tests
        Failures = [int]$suite.failures
        Errors = [int]$suite.errors
        Skipped = [int]$suite.skipped
    }
}
$results | Format-Table -AutoSize
Write-Host "---"
$t = ($results | Measure-Object Tests -Sum).Sum
$f = ($results | Measure-Object Failures -Sum).Sum
$e = ($results | Measure-Object Errors -Sum).Sum
$s = ($results | Measure-Object Skipped -Sum).Sum
Write-Host "Total: $t tests, $f failures, $e errors, $s skipped"
