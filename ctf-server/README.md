# Capture the Flag: Server (Member A)

Authoritative Java game server. TCP handles reliable messages (lobby, events, chat), and UDP handles fast game data (inputs and world snapshots), 30 times per second. Plain Java 17+ with no libraries.

## Run it

```
javac -d out src/*.java test/*.java
cd out
java GameServer 5000 2        # port, minimum ready players to start
```

Test without a game window by opening two more terminals in `out/`:

```
java TestBot localhost 5000 1 idle 60
java TestBot localhost 5000 0 attack 60     # should win 3-0 in about 30 seconds
```

Other bot modes: `defend` (tags intruders) and `grab` (takes the flag and then disconnects, to test flag drop and auto-return).

## Files

| File | What it does |
|---|---|
| `GameServer.java` | `main`: starts the UDP thread and the tick thread, then the TCP accept loop (one thread per client) |
| `ClientHandler.java` | One per client. A reader thread for incoming TCP lines and a writer thread with an outgoing queue |
| `UdpServer.java` | `DatagramSocket` receive loop for inputs, and `send()` for snapshots |
| `GameState.java` | All game logic: lobby, tick loop, movement, tagging, respawn, flag state machine, scoring, timer |
| `Flag.java` | Flag with three states: `AT_BASE`, `CARRIED`, `DROPPED` |
| `Player.java` | Player data: position, team, keys held, UDP address |
| `GameMap.java` | Map size, bases, walls and collision. Member B must draw the same walls |
| `test/TestBot.java` | Fake client for testing |

## Threads and concurrency

```
main thread ──accept()──> client-N thread (reads TCP) ─┐
                          client-N-writer (writes TCP)  │
udp-receiver thread ──────────────────────────────────┼──> GameState (synchronized)
tick-loop thread (every 33 ms) ────────────────────────┘
```

- Every public `GameState` method is `synchronized`, so only one thread changes the game at a time. This prevents race conditions, such as two players grabbing the same flag in the same instant.
- `ClientHandler.send()` only adds a line to a `LinkedBlockingQueue`. A separate writer thread does the slow network write, so a laggy client can never freeze the game loop. A client that stops reading entirely (more than 1000 lines queued) is disconnected.
- The tick loop uses `ScheduledExecutorService.scheduleAtFixedRate` and wraps each tick in try/catch, so one error can't stop the game.

## One tick (30 times per second)

1. Respawn players whose 3-second timer is up
2. Move players using the keys they hold (speed is set by the server, and the carrier is 15% slower)
3. Tagging: if two enemies touch, the one in the *other team's half* is tagged
4. Flag state machine (below)
5. Round timer (5 minutes)

Then the server sends one snapshot to every client over UDP.

## Flag state machine

| From | Event | To |
|---|---|---|
| AT_BASE | enemy touches it | CARRIED |
| CARRIED | carrier tagged or disconnects | DROPPED (where they were) |
| DROPPED | enemy touches it | CARRIED |
| DROPPED | teammate touches it | AT_BASE (returned) |
| DROPPED | 10 s with no touch | AT_BASE |
| CARRIED | carrier enters own base **and** own flag is AT_BASE | AT_BASE, +1 score |

First to 3 captures wins, otherwise the higher score when the timer runs out. After a round everyone returns to the lobby and presses READY to play again.

## Anti-cheat (server checks)

- Clients send only which keys are held, never positions, so speed hacks and teleporting are impossible.
- Every UDP packet must include a secret `token` the client received over TCP, so nobody can send inputs for another player.
- Old or out-of-order UDP packets are ignored using the sequence number.
- Malformed packets and unknown commands are ignored.

## Protocol (for Members B and C)

All messages are text, separated by spaces. TCP messages end with a newline.

**Client → server (TCP)**

| Message | Meaning |
|---|---|
| `HELLO <team> <name>` | Join. Team is 0 red, 1 blue, or -1 for auto. Must be the first message |
| `READY` / `UNREADY` | Lobby ready toggle. The round starts when everyone is ready |
| `TEAM <0or1>` | Switch team (lobby only) |
| `PING <anything>` | Server replies `PONG <same>`. Use `System.nanoTime()` for ping display |
| `CHAT <text>` | Chat message |
| `BYE` | Disconnect |

**Server → client (TCP)**

| Message | Meaning |
|---|---|
| `WELCOME <id> <token> <udpPort> <team>` | Sent after HELLO. Save the id and token |
| `LOBBY <phase> <minPlayers> id:team:ready:name,...` | Lobby list, sent whenever it changes |
| `START` | Round started |
| `EVENT <text>` | Show in the event feed, for example "X captured the Blue flag!" |
| `GAMEOVER <winner> <redScore> <blueScore>` | Winner is 0, 1, or -1 for a draw |
| `CHAT <name>: <text>` | Chat line |
| `PONG <same>` / `ERROR <text>` | |

**Client → server (UDP)**, send every frame or at least 30 times per second:

```
I <id> <token> <seq> <keys>
```
`seq` goes up by 1 each packet. `keys` is a bitmask: 1 up, 2 down, 4 left, 8 right (so up+right = 9). The first packet also tells the server where to send snapshots.

**Server → client (UDP)**, 30 times per second:

```
S <tick> <phase> <msLeft> <redScore> <blueScore>
  <redFlagState> <x> <y> <carrierId>  <blueFlagState> <x> <y> <carrierId>
  <playerCount> then for each player: <id> <team> <x> <y> <alive 1/0> <lastSeq>
```

`lastSeq` is the last input the server applied for that player. Member B/C can use it for client-side prediction: keep the inputs not yet acknowledged, start from the server position, and replay them.

Map: 1200 × 700, player radius 15, flag radius 12, base radius 50. Red base at (100, 350), blue base at (1100, 350). Walls are listed in `GameMap.java`.

## Test results

| Test | Result |
|---|---|
| Attacker vs idle | Lobby → START → 3 pickups and captures → `GAMEOVER 0 3 0`, about 30 snapshots per second received |
| Attacker vs defender | Intruder tagged repeatedly and respawned at base |
| Carrier disconnects | Flag dropped, then returned to base after 10 s |
| All runs | No exceptions |
