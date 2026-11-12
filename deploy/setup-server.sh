#!/usr/bin/env bash
# Runs ON the cloud server (deploy/upload.ps1 starts it). Safe to run again: it only does what is still missing,
# then restarts the game server so a new upload takes effect.
set -euo pipefail

if ! command -v java >/dev/null; then
  echo "== Installing Java 17"
  sudo apt-get update -y
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-17-jre-headless iptables-persistent
fi

# Oracle's Ubuntu images block everything except SSH in the machine's own firewall: open the game port (TCP and UDP)
for proto in tcp udp; do
  if ! sudo iptables -C INPUT -p "$proto" --dport 5000 -j ACCEPT 2>/dev/null; then
    echo "== Opening $proto port 5000"
    sudo iptables -I INPUT -p "$proto" --dport 5000 -j ACCEPT
  fi
done
sudo netfilter-persistent save >/dev/null 2>&1 || true   # keep the rules after a reboot

echo "== Starting the game server"
sudo cp ~/thronefall/thronefall.service /etc/systemd/system/thronefall.service
sudo systemctl daemon-reload
sudo systemctl enable thronefall >/dev/null 2>&1
sudo systemctl restart thronefall
sleep 2
sudo systemctl --no-pager --lines=5 status thronefall
