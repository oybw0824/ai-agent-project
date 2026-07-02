$body = '{"question":"hello"}'
try {
    $response = Invoke-WebRequest -Uri 'http://localhost:8082/chat/stream' -Method POST -ContentType 'application/json' -Body $body -TimeoutSec 20
    [System.IO.File]::WriteAllText('D:\project\ai-agent-project\sse-raw.txt', $response.Content, [System.Text.Encoding]::UTF8)
    Write-Host "OK: length=$($response.Content.Length)"
    Write-Host "First 500 chars:"
    Write-Host $response.Content.Substring(0, [Math]::Min(500, $response.Content.Length))
} catch {
    Write-Host "ERROR: $($_.Exception.Message)"
}
