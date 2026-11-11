@echo off
cd /d "%~dp0"
echo Starting Capture the Flag Client (Lobby / Multiplayer)...
java --enable-native-access=ALL-UNNAMED -cp out;lib/* ctf.client.Main localhost
pause
