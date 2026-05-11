$env:JAVA_HOME = "C:\Program Files\Java\jdk-22"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$logFile = "D:\Star\ai-travel-guide\embed_test.log"
$errFile = "D:\Star\ai-travel-guide\embed_test_err.log"
$process = Start-Process -FilePath "mvn.cmd" `
    -ArgumentList "spring-boot:run" `
    -WorkingDirectory "D:\Star\ai-travel-guide" `
    -PassThru `
    -NoNewWindow `
    -RedirectStandardOutput $logFile `
    -RedirectStandardError $errFile
Write-Host "Started PID: $($process.Id)"
