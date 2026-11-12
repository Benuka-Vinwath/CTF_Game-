# Builds the game, uploads it to the cloud server and (re)starts the game server there.
# First time and every update: the same command.
#   .\deploy\upload.ps1 -Ip 140.238.12.34
# Optional: -Key path\to\private.key (default: .ssh\thronefall.key in your user folder), -User ubuntu
param(
    [Parameter(Mandatory = $true)][string]$Ip,
    [string]$Key = (Join-Path $HOME ".ssh\thronefall.key"),
    [string]$User = "ubuntu"
)
$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot)   # the project folder

if (-not (Test-Path $Key)) { Write-Host "SSH key not found: $Key (see docs\ONLINE-SERVER.md, step 2)"; exit 1 }

Write-Host "== Compiling"
$files = (Get-ChildItem src -Recurse -Filter *.java).FullName
javac --release 17 -d out $files
if ($LASTEXITCODE -ne 0) { Write-Host "Compile failed"; exit 1 }

$target = "$User@$Ip"
Write-Host "== Uploading to $target"
ssh -i $Key -o StrictHostKeyChecking=accept-new $target "mkdir -p ~/thronefall"
if ($LASTEXITCODE -ne 0) { Write-Host "Could not reach the server over SSH. Check the IP, the key and that the instance is running."; exit 1 }
scp -i $Key -r -q out deploy\thronefall.service deploy\setup-server.sh "${target}:~/thronefall/"
if ($LASTEXITCODE -ne 0) { Write-Host "Upload failed"; exit 1 }

Write-Host "== Setting up and starting the server"
ssh -i $Key $target "bash ~/thronefall/setup-server.sh"
if ($LASTEXITCODE -ne 0) { Write-Host "Setup failed (see the messages above)"; exit 1 }

Write-Host ""
Write-Host "Done. Test it from this laptop:  Test-NetConnection $Ip -Port 5000"
Write-Host "Then put  ${Ip}:5000  in online-server.txt"
