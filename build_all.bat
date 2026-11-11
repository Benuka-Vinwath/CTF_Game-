@echo off
cd /d "%~dp0"
if not exist out mkdir out
echo ============================================
echo  Compiling the CTF game (server + client)
echo ============================================

javac --release 17 -d out ^
  src\ctf\common\MapData.java ^
  src\ctf\common\LobbyMap.java ^
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
  src\ctf\client\LobbyRoom.java ^
  src\ctf\client\GamePanel.java ^
  src\ctf\client\GameView.java ^
  src\ctf\client\Profile.java ^
  src\ctf\client\MenuScreen.java ^
  src\ctf\client\GameWindow.java ^
  src\ctf\client\Main.java ^
  src\ctf\client\SelfCheck.java ^
  src\ctf\client\Demo.java ^
  src\ctf\tools\ConsoleClient.java

if errorlevel 1 (
  echo [FAIL] Compile failed.
  pause & exit /b 1
)
echo [OK] Compiled.
echo.
echo ============================================
echo  Build complete!  Run order:
echo    1. run_server.bat   (host only)
echo    2. run_client.bat   (every player; you land in the lobby room)
echo  Or quick_test_2_players.bat to try it alone.
echo ============================================
pause
