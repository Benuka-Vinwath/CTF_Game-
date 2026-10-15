@echo off
cd /d "%~dp0"
set SERVER=%1
if "%SERVER%"=="" set SERVER=172.20.10.6
echo Starting Capture the Flag Client connecting to %SERVER%...
java -cp out ctf.client.Main %SERVER%
pause
