@echo off
cd /d "%~dp0"
echo Starting Capture the Flag Game Server on port 5000 (1 player required to start)...
java -cp out ctf.server.GameServer 5000 1
pause
