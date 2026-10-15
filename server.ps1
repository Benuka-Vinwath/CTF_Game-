# Compiles the whole project and starts the game server (TCP and UDP port 5000).
# Usage:   .\server.ps1          (the round starts when 2 players are ready)
#          .\server.ps1 1        (the round starts when 1 player is ready: handy for testing alone)
param([int]$MinPlayers = 2)

Set-Location $PSScriptRoot

$files = (Get-ChildItem src -Recurse -Filter *.java).FullName
javac --release 17 -d out $files
if ($LASTEXITCODE -ne 0) { Write-Host "Compile failed"; exit 1 }

java -cp out ctf.server.GameServer 5000 $MinPlayers
