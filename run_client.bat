@echo off
cd /d "%~dp0"
set SERVER=%1
if "%SERVER%"=="" set SERVER=localhost
echo Starting Capture the Flag Client connecting to %SERVER%...
java --enable-native-access=ALL-UNNAMED -cp "out;lib/*" ctf.client.Main %SERVER%
pause
