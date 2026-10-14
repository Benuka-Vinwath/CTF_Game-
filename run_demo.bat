@echo off
cd /d "%~dp0"
echo Starting Capture the Flag Demo (Single Player with Bots)...
java -cp out ctf.client.Demo
pause
