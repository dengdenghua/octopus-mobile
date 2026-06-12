$url = 'https://plugins.jetbrains.com/api/plugins/13710/updates?size=20'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$resp = Invoke-WebRequest -Uri $url -UseBasicParsing -Headers @{'Accept'='application/json'} -Method GET
$json = $resp.Content | ConvertFrom-Json
foreach ($u in $json) {
    Write-Host "id=$($u.id) version=$($u.version) build=$($u.build) size=$($u.size)"
}
