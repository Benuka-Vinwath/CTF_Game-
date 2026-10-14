@echo off
cd /d "%~dp0"
echo Starting Capture the Flag Client (Lobby / Multiplayer)...
java -cp out ctf.client.Main localhost
pause
