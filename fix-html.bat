@echo off
setlocal enabledelayedexpansion

set "BASE=D:\project\ai-agent-project\agent-server\src\main\resources\static"
set "TMPFILE=%BASE%\index_head.tmp"

:: Read first 928 lines
powershell -NoProfile -Command "[System.IO.File]::WriteAllLines('%TMPFILE%', [System.IO.File]::ReadAllLines('%BASE%\index.html')[0..927], [System.Text.Encoding]::UTF8)"

:: Combine head + fix
powershell -NoProfile -Command "$h = [System.IO.File]::ReadAllLines('%TMPFILE%'); $t = [System.IO.File]::ReadAllLines('%BASE%\render-md-fix.txt'); $c = $h + $t; [System.IO.File]::WriteAllLines('%BASE%\index.html', $c, [System.Text.Encoding]::UTF8); Write-Host ('Done: ' + $c.Count + ' lines')"

:: Cleanup
del "%TMPFILE%" 2>nul
