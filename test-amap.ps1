$webKey = "86273e71807139c717a2c650f15c792b"

Write-Host "=== Test 1: Geocode ==="
$url1 = "https://restapi.amap.com/v3/geocode/geo?key=$webKey&address=%E5%8E%A6%E9%97%A5%E5%A4%A7%E5%AD%A6&city=%E5%8E%A6%E9%97%A8"
try {
    $body = (Invoke-WebRequest -Uri $url1 -UseBasicParsing).Content
    Write-Host $body
} catch { Write-Host "ERROR: $($_.Exception.Message)" }

Write-Host "`n=== Test 2: POI Search ==="
$url2 = "https://restapi.amap.com/v3/place/text?key=$webKey&keywords=%E5%8D%97%E6%99%AE%E6%8B%96%E5%AF%BA&city=%E5%8E%A6%E9%97%A8&offset=1"
try {
    $body2 = (Invoke-WebRequest -Uri $url2 -UseBasicParsing).Content
    Write-Host $body2
} catch { Write-Host "ERROR: $($_.Exception.Message)" }

Write-Host "`n=== Test 3: POI Special Chars ==="
$url3 = 'https://restapi.amap.com/v3/place/text?key=' + $webKey + '&keywords=' + [System.Uri]::EscapeDataString('如家酒店·neo(厦门SM广场店)') + '&city=%E5%8E%A6%E9%97%A8&offset=1'
try {
    $body3 = (Invoke-WebRequest -Uri $url3 -UseBasicParsing).Content
    Write-Host $body3
} catch { Write-Host "ERROR: $($_.Exception.Message)" }
