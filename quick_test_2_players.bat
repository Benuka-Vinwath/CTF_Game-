@echo off
cd /d "%~dp0"
echo ========================================================
echo  Quick Test: Starting Lobby Server + 2 Clients
echo ========================================================

start "Lobby Server" cmd /k "java -cp out lobby.server.LobbyServer 6000 2 5000"
timeout /t 2 /nobreak > nul

start "Player 1 (Alice)" cmd /k "java -cp out lobby.client.LobbyWindow localhost"
timeout /t 1 /nobreak > nul

start "Player 2 (Bob)"   cmd /k "java -cp out lobby.client.LobbyWindow localhost"
echo.
echo Both clients opened!
echo 1. Click Connect on both windows
echo 2. Walk around with WASD
echo 3. Chat using the bottom bar
echo 4. Click Ready on both
echo 5. Watch the 3-second countdown launch the real CTF game!
echo ========================================================

