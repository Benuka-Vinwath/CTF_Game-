@echo off
cd /d "%~dp0"
if not exist out mkdir out
echo ============================================
echo  Compiling ALL (CTF game + Visual Lobby)
echo ============================================

echo [1/2] Compiling CTF game...
javac -d out ^
  src\ctf\common\MapData.java ^
  src\ctf\common\Protocol.java ^
  src\ctf\common\Snapshot.java ^
  src\ctf\server\Flag.java ^
  src\ctf\server\Player.java ^
  src\ctf\server\Conn.java ^
  src\ctf\server\GameState.java ^
  src\ctf\server\UdpServer.java ^
  src\ctf\server\ClientHandler.java ^
  src\ctf\server\GameServer.java ^
  src\ctf\client\net\NetEvents.java ^
  src\ctf\client\net\NetClient.java ^
  src\ctf\client\NetLink.java ^
  src\ctf\client\Type.java ^
  src\ctf\client\Art.java ^
  src\ctf\client\Sfx.java ^
  src\ctf\client\HD.java ^
  src\ctf\client\Scenery.java ^
  src\ctf\client\GamePanel.java ^
  src\ctf\client\GameView.java ^
  src\ctf\client\LobbyWindow.java ^
  src\ctf\client\Main.java ^
  src\ctf\client\SelfCheck.java ^
  src\ctf\client\Demo.java ^
  src\ctf\tools\ConsoleClient.java

if errorlevel 1 (
  echo [FAIL] CTF compile failed.
  pause & exit /b 1
)
echo [OK] CTF game compiled.

echo [2/2] Compiling Visual Lobby...
javac -cp out -d out ^
  src\lobby\common\LobbyProtocol.java ^
  src\lobby\common\LobbyMap.java ^
  src\lobby\server\LobbyServer.java ^
  src\lobby\client\LobbyEvents.java ^
  src\lobby\client\LobbyNet.java ^
  src\lobby\client\LobbyPanel.java ^
  src\lobby\client\LobbyWindow.java

if errorlevel 1 (
  echo [FAIL] Lobby compile failed.
  pause & exit /b 1
)
echo [OK] Visual Lobby compiled.
echo.
echo ============================================
echo  Build complete!  Run order:
echo    1. run_lobby_server.bat   (host only)
echo    2. run_lobby_client.bat   (every player)
echo ============================================
pause

