@echo off
cd /d "%~dp0"
echo ========================================================
echo  Starting Visual Lobby Server (port 6000, 2 players min)
echo  When all players ready up, the CTF server (port 5000)
echo  will automatically start!
echo ========================================================
java -cp out lobby.server.LobbyServer 6000 2 5000
pause
