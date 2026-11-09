@echo off
cd /d "%~dp0"
set HOST=localhost
if not "%~1"=="" set HOST=%~1
echo ========================================================
echo  Starting Visual Lobby Client
echo  Connecting to: %HOST%:6000
echo ========================================================
java -cp out lobby.client.LobbyWindow %HOST%
pause
