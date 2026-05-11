$webKey = "86273e71807139c717a2c650f15c792b"
Write-Host "=== Test Geocode with simple address ==="
$url = "https://restapi.amap.com/v3/geocode/geo?key=$webKey&address=%E7%A6%8F%E5%BB%BA%E7%9C%81%E5%8E%A6%E9%97%A8%E5%B8%82%E6%80%9D%E6%98%8E%E5%8C%BA%E4%B8%AD%E5%B1%B1%E8%B7%AF128%E5%8F%B7"
try { $r = (Invoke-WebRequest -Uri $url -UseBasicParsing).Content; Write-Host $r } catch { Write-Host "ERROR" }

Write-Host "`n=== Test Geocode with English ==="
$url2 = "https://restapi.amap.com/v3/geocode/geo?key=$webKey&address=Beijing&city=beijing"
try { $r2 = (Invoke-WebRequest -Uri $url2 -UseBasicParsing).Content; Write-Host $r2 } catch { Write-Host "ERROR" }
