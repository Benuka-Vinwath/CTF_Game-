@echo off
cd /d "%~dp0"
echo ============================================
echo  Compiling lobby (server + client + common)
echo ============================================

if not exist out mkdir out

javac -cp out -d out ^
  src\lobby\common\LobbyProtocol.java ^
  src\lobby\common\LobbyMap.java ^
  src\lobby\server\LobbyServer.java ^
  src\lobby\client\LobbyEvents.java ^
  src\lobby\client\LobbyNet.java ^
  src\lobby\client\LobbyPanel.java ^
  src\lobby\client\LobbyWindow.java

if errorlevel 1 (
    echo.
    echo [ERROR] Compilation failed.
    pause
    exit /b 1
)

echo.
echo [OK] Lobby compiled successfully.
echo.
echo To run the server:  run_lobby_server.bat
echo To run a client:    run_lobby_client.bat [serverHost]
pause

