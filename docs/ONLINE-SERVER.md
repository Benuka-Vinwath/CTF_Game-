# The ThroneFall online server

Two ways to play, both in the same game:

| | How | Who can join |
|---|---|---|
| **Local** (unchanged) | One player clicks **HOST**, the others **JOIN** with the address on the Host card (`192.168.x.x`) | Players on the same Wi-Fi |
| **Online** (new) | Everyone clicks **ONLINE** on the Join card (next to JOIN) | Anyone, anywhere, also on mobile data |

The online server is the normal `GameServer`, running on a free always-on cloud computer. Nobody has to host.

---

## 1. Create a free Oracle Cloud account (about 10 minutes, done once)

1. Go to **oracle.com/cloud/free** and click **Start for free**.
2. **Home region:** choose **India West (Mumbai)**, **India South (Hyderabad)** or **Singapore**, which are the closest to Sri Lanka and give the lowest ping. *You cannot change it later.*
3. Oracle asks for a card to check you are a real person. The "Always Free" machine used below costs nothing.

## 2. Create the server (the "instance")

1. In the Oracle console: **Compute → Instances → Create instance**. Name it `thronefall`.
2. **Image:** click *Change image* and choose **Canonical Ubuntu 22.04**.
3. **Shape:** keep an **Always Free–eligible** one: *VM.Standard.E2.1.Micro*, or *Ampere VM.Standard.A1.Flex* with 1 OCPU and 6 GB.
4. **Networking:** keep the defaults, and make sure **Assign a public IPv4 address** is on.
5. **Add SSH keys:** choose **Generate a key pair for me** and click **Save private key**.
   Save that file as `C:\Users\<you>\.ssh\thronefall.key`, creating the `.ssh` folder if it is missing.
6. Click **Create**. When the instance is *Running*, copy its **Public IP address**, for example `140.238.12.34`.

Windows only lets SSH use a key that only you can read. Run this once in PowerShell:

```powershell
icacls "$HOME\.ssh\thronefall.key" /inheritance:r /grant:r "$($env:USERNAME):(R)"
```

## 3. Open the game port in Oracle's network firewall

The game uses **port 5000, both TCP (lobby, chat) and UDP (movement)**.

1. Open your instance → under **Primary VNIC** click the **Subnet** link → **Security Lists** → the default security list.
2. **Add Ingress Rules**, twice:

| Source CIDR | IP Protocol | Destination Port Range |
|---|---|---|
| `0.0.0.0/0` | **TCP** | `5000` |
| `0.0.0.0/0` | **UDP** | `5000` |

(The firewall *inside* the machine is opened by the setup script in the next step.)

## 4. Upload and start the game server (one command)

From the project folder on your laptop:

```powershell
.\deploy\upload.ps1 -Ip 140.238.12.34
```

It compiles the game, uploads it, installs Java on the server, opens port 5000 there, and starts the game server as a service.
The service **starts again by itself** after a crash or a reboot. The first run takes a few minutes; later runs take seconds.

Check it from your laptop:

```powershell
Test-NetConnection 140.238.12.34 -Port 5000
```

`TcpTestSucceeded : True` means the server is reachable.

## 5. Turn on the ONLINE button

Open `online-server.txt` in the game folder and add the address on its own line:

```
140.238.12.34:5000
```

Start the game: **PLAY → ONLINE**. Give your friends the game **including `online-server.txt`**, and they get the same ONLINE button.

---

## Updating the server

1. Change the game.
2. If anything about the network messages changed, raise `VERSION` in `src/ctf/common/Protocol.java`. Players with an older copy then get a clear "get the latest version" message instead of strange errors.
3. Run `.\deploy\upload.ps1 -Ip <ip>` again, and send friends the new build.

## Useful commands

```powershell
# live server log (Ctrl+C to stop watching)
ssh -i "$HOME\.ssh\thronefall.key" ubuntu@140.238.12.34 "journalctl -u thronefall -f"

# restart or stop the game server
ssh -i "$HOME\.ssh\thronefall.key" ubuntu@140.238.12.34 "sudo systemctl restart thronefall"
ssh -i "$HOME\.ssh\thronefall.key" ubuntu@140.238.12.34 "sudo systemctl stop thronefall"
```

## If something does not work

| Problem | Fix |
|---|---|
| `upload.ps1` cannot connect over SSH | Check the IP, that the instance is *Running*, and step 2's `icacls` command. |
| `TcpTestSucceeded : False` | Step 3: both ingress rules (TCP **and** UDP 5000) on the subnet's security list. |
| You join the lobby, but nobody moves | UDP 5000 is missing in step 3. |
| "Your game is version X but this server runs Y" | Rebuild the game from the same code that was uploaded (or upload again). |
| The ONLINE button is missing | `online-server.txt` must sit next to the game (the folder you start it from) and contain the address on a line without `#`. |

## Notes

- One server process runs **one lobby and match at a time**, up to 8 players. For more groups at once, run a second service on another port (copy `thronefall.service`, change `5000`, and open that port too).
- Players who lose their connection are removed after 15 seconds of silence, so they don't block the lobby.
- Profiles and match history stay on each player's own computer (`profile.db`). The server keeps none.
