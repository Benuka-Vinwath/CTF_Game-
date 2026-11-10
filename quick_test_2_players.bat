@echo off
cd /d "%~dp0"
echo ========================================================
echo  Quick Test: game server + 2 clients on this computer
echo ========================================================

start "CTF Server" cmd /k "java -cp out ctf.server.GameServer 5000 2"
timeout /t 2 /nobreak > nul

start "Player 1" cmd /k "java -cp out ctf.client.Main localhost"
timeout /t 1 /nobreak > nul

start "Player 2" cmd /k "java -cp out ctf.client.Main localhost"
echo.
echo Both clients opened!
echo 1. Click Connect on both windows: you land in the lobby room
echo 2. Walk with WASD, stand on the RED or BLUE pad to pick a team
echo 3. ENTER to chat, shoot the practice targets, visit the wardrobe (E / C)
echo 4. Both click I'M READY, then the host (first to join) clicks START MATCH
echo ========================================================
