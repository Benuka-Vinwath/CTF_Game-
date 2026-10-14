# Compiles the whole project and starts the game.
# Usage:   .\play.ps1                  (server on this computer)
#          .\play.ps1 192.168.1.25     (server on another computer)
param([string]$Server = "localhost")

# The game loads assets/background.png relative to where it is started, so always start from this folder.
Set-Location $PSScriptRoot

$files = (Get-ChildItem src -Recurse -Filter *.java).FullName
javac --release 17 -d out $files
if ($LASTEXITCODE -ne 0) { Write-Host "Compile failed"; exit 1 }

# sun.java2d.d3d=false: avoids a blank white window on some AMD Radeon laptops
java "-Dsun.java2d.d3d=false" -cp out ctf.client.Main $Server
