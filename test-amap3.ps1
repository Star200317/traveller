$webKey = "86273e71807139c717a2c650f15c792b"
$base = "https://restapi.amap.com/v3/geocode/geo?key=$webKey"

function Test-Geo($desc, $addr, $city) {
    $url = "$base&address=$addr"
    if ($city) { $url += "&city=$city" }
    try {
        $body = (Invoke-WebRequest -Uri $url -UseBasicParsing).Content
        $json = $body | ConvertFrom-Json
        Write-Host "$desc => status=$($json.status) info=$($json.info)"
    } catch { Write-Host "$desc => EXCEPTION" }
}

Test-Geo "1. pure name:厦门大学" "%E5%8E%A6%E9%97%A5%E5%A4%A7%E5%AD%A6" ""
Test-Geo "2. name+city:厦门大学" "%E5%8E%A6%E9%97%A5%E5%A4%A7%E5%AD%A6" "%E5%8E%A6%E9%97%A8"
Test-Geo "3. city+name:厦门厦门大学" "%E5%8E%A6%E9%97%A8%E5%8E%A6%E9%97%A5%E5%A4%A7%E5%AD%A6" ""
Test-Geo "4. full addr:厦门市思明区思明南路422号" "%E5%8E%A6%E9%97%A8%E5%B8%82%E6%80%9D%E6%98%8E%E5%8C%BA%E6%80%9D%E6%98%8E%E5%8D%97%E8%B7%AF422%E5%8F%B7" ""
Test-Geo "5. full addr+city" "%E5%8E%A6%E9%97%A8%E5%B8%82%E6%80%9D%E6%98%8E%E5%8C%BA%E6%80%9D%E6%98%8E%E5%8D%97%E8%B7%AF422%E5%8F%B7" "%E5%8E%A6%E9%97%A8"
Test-Geo "6. 南普陀寺(POI名)" "%E5%8D%97%E6%99%AE%E6%8B%96%E5%AF%BA" "%E5%8E%A6%E9%97%A8"
Test-Geo "7. 鼓浪屿" "%E9%BC%93%E6%B5%AA%E5%B6%BC" "%E5%8E%A6%E9%97%A8"
