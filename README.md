# SiteBlock

Blocks sites on your machine. No extension, no installed app, no account.
You type the domain, and it writes to the system hosts file — the same
mechanism schools and offices use to block sites on the LAN.

Works on Linux, macOS, and Windows.

## What it does

```bash
sudo siteblock block tiktok.com --for 4h   # blocks for 4 hours
siteblock status                            # what's blocked and for how long
sudo siteblock unblock tiktok.com          # unblocks
```

Without `--for`, a block has no deadline: it stays until you remove it.

There is also an optional DNS server that extends blocking to other devices
on the same network — phone, tablet, console. See `siteblock dns`.

## Requirements

- **Java 26 or newer**. It is the project's most unusual requirement: the
  code uses Java 26 features and does not compile on earlier versions. CI uses
  Temurin 26.
- Maven 3.8+ to compile. Not needed to run.
- Linux, macOS, or Windows. On Windows, open the terminal as Administrator.

## Install

```bash
git clone https://github.com/Campeol-G/SiteBlock.git
cd SiteBlock
mvn package
```

The executable is produced at `sitelock-app/target/siteblock-*.jar`.

## Run

Add the command to your PATH:

```bash
ln -sf "$PWD/bin/siteblock" ~/.local/bin/siteblock
```

Then run it directly:

```bash
sudo siteblock block tiktok.com --for 1h
siteblock status
```

If you don't install it into your PATH, use the scripts; they find the jar
themselves:

```bash
./run.sh block tiktok.com --for 1h    # Linux and macOS
run.bat block tiktok.com --for 1h     # Windows
```

If the jar is somewhere else, point to it with `SITEBLOCK_JAR=/path/siteblock.jar`.

## Commands

```bash
# Block. Multiple domains at once:
sudo ./run.sh block tiktok.com vm.tiktok.com --for 30m
sudo ./run.sh block twitter.com               # no --for = no deadline

# See what's blocked and how much time is left
./run.sh status

# Unblock. If the deadline has not passed, it asks for --force and a typed confirmation:
sudo ./run.sh unblock tiktok.com
sudo ./run.sh unblock tiktok.com --force

# Delete the block history (does not touch hosts)
./run.sh reset --yes

# DNS server for the local network
sudo ./run.sh dns start      # also blocks on phone/tablet/console
sudo ./run.sh dns start --allow-from 192.168.1.50,10.8.0.0/24   # allows other sources
./run.sh dns status
sudo ./run.sh dns stop
sudo ./run.sh dns stop --force  # stops before the deadline, with confirmation

# Named domain sets
sudo ./run.sh save profile work      # saves what's currently blocked
./run.sh profile list
./run.sh profile work apply             # reloads without ever shortening the deadline
sudo ./run.sh profile work unblock --force
./run.sh delete profile work         # deletes the profile (asks for confirmation)

# Create an empty profile without blocking anything now
./run.sh create profile work

# Edit the recipe of an existing profile
./run.sh profile work add tiktok.com            # permanent
./run.sh profile work add tiktok.com --for 4h   # adds it with a 4h deadline
./run.sh profile work remove tiktok.com          # only removes it from the recipe

# Expiration daemon (deadlines expire on their own)
sudo ./run.sh daemon enable             # installs it to start on every boot
./run.sh daemon status                  # answers without sudo
sudo ./run.sh daemon start              # starts it now, in the background
sudo ./run.sh daemon stop
sudo ./run.sh daemon disable
```

Accepted durations: `30m`, `2h`, `1h30m`, `1d`, `1d2h30m`. Lowercase only, no
spaces. The limit is 1,000 years; anything above that is rejected.

### Exit codes

Every command returns an exit code, for anyone who wants to build scripts on top of it:

| Code | When |
|---|---|
| `0` | success |
| `1` | error (could not read or write the file, profile does not exist) |
| `2` | wrong usage (missing argument, invalid value, unknown command) |
| `3` | refused or cancelled (refused to stop before the deadline, cancelled the confirmation) |

### Why it needs sudo

Editing the hosts file requires administrator permission on all three systems.
`block`, `unblock`, `profile <name> apply`, `profile <name> unblock`, and `dns start` need it.
`status`, `reset`, `profile list`, and `daemon status` do not.

The daemon commands (`enable`, `start`, `stop`, `disable`) touch the system —
autostart, service, root process — and also need elevation.

## Where things are

In `~/.sitelock/`:

| File | What it is |
|---|---|
| `blocks.json` | what's blocked and until when |
| `blocks.json.lock` | lock: serializes processes that modify state concurrently |
| `hosts.backup` | copy of the original hosts file, created once |
| `hosts.lock` | lock: serializes writes to the hosts file |
| `dns-server.json` | DNS server state |
| `dns-server.start.lock` | lock: prevents two `dns start` runs at the same time |
| `dns-server.log` | DNS server log (queries, upstream, decisions) |
| `dns-server.log.1` | previous log, once the current one exceeds 5 MiB |
| `profiles.json` | saved named domain sets |
| `profiles.json.lock` | lock: serializes profile writes |
| `daemon.json` | expiration daemon state (pid, last sweep) |
| `daemon.lock` | lock: guarantees only one daemon, even with two `daemon start` runs |
| `daemon.log` | expiration daemon log (what it released, errors) |

The system hosts file is only modified inside the block delimited by markers,
which sits between one of your original lines and the rest of the file. It
never overwrites what was already there.

## Limits

Things that **do not work**, worth knowing up front:

- **It does not block by app.** It blocks by domain. If TikTok opens through
  `app.net`, it gets through.
- **In hosts mode, it does not cover subdomains.** `tiktok.com` covers
  `tiktok.com` and `www.tiktok.com`, but not `vm.tiktok.com`. List the
  subdomains you use. In DNS mode, coverage is different: the server answers
  for all subdomains of a blocked domain, without listing them one by one.
- **DNS mode listens on all interfaces.** The server listens on
  `0.0.0.0:53` and forwards anything that is not blocked to the upstream DNS.
  That is how it reaches the local network — and how it becomes an open
  resolver if the router lets port 53 through to the internet. **Do not expose
  port 53 outside your network.**
- **The DNS server refuses anyone not on the local network.** Outside sources
  are ignored silently, and `--allow-from` (on `dns start`) is what allows
  additional ones.
- **Apps with their own DNS or a fixed IP bypass it.** Some apps (mostly
  Android and Electron) resolve on their own and ignore hosts.
- **Private browsing, extensions, and some browsers** may ignore hosts.
- **Expired blocks are only removed automatically if the daemon is running.**
  With `sudo siteblock daemon enable` installed, the deadline expires without
  you opening the terminal — even after a reboot. Without it, the site is only
  released when you run another siteblock command (`status` already does the
  trick), and `block --for` warns right away that the daemon is not covering it.
- **It will not stop you.** You own the machine and can edit
  `/etc/hosts` with `sudo`. SiteBlock is a lock against distraction, not a
  security mechanism.
- **It needs Java 26.** On a machine with Java 17, 21, or 25, it will not run.

## How it works

SiteBlock writes to the system hosts file:

```
# SITELOCK-START
127.0.0.1 tiktok.com
127.0.0.1 www.tiktok.com
::1 tiktok.com
::1 www.tiktok.com
# SITELOCK-END
```

It only rewrites that block. Everything above and below it is never touched.
`hosts.backup` is a copy of the full file before the first change, in case
you want to undo everything manually.

When you block, the deadline is saved as an exact time in `blocks.json`,
not as "4 hours from now". That is why a block survives a reboot or shutdown:
if you blocked for 4 hours, used 2 hours, and shut down, you still have
1h30m left when you come back.

The `daemon` closes the other half of that loop: on its own, it wakes up, sees
what expired, and removes it from `/etc/hosts`. It **only removes** — blocking
is still done by `block` and `profile <name> apply` — and writes the result to
`daemon.json` so that `daemon status` can show when something last expired on
its own.

Profiles store a named set of domains so you don't have to rebuild the same
list every day. What is saved is the **remaining deadline** at save time,
never the original time: a profile is a recipe, not a history. Applying a
profile **only increases** the deadline, never shortens it — repeating
`profile <name> apply` several times a day is safe.

`profile <name> add` and `profile <name> remove` edit the recipe of an
existing profile, without going through `block`: `add` saves the domain with
the requested deadline (or as permanent, without `--for`), and `remove`
removes it from the recipe. Neither touches `/etc/hosts` — what is blocked
stays blocked, and `remove` warns when a domain that was removed from the
recipe is still blocked, because `profile <name> unblock` only reaches what
is still in the profile.

To install autostart (recommended after installation):

```bash
sudo siteblock daemon enable     # Linux: systemd unit · macOS: LaunchDaemon · Windows: task
siteblock daemon status          # without sudo: answers if it is running
```

The daemon runs as root because it needs to write to `/etc/hosts`. It saves
paths in the autostart artifact and gives ownership of the files it writes
back to your user, so `status` works without elevation.

`daemon enable` also **installs a copy of the jar** in a root directory
(`/opt/sitelock` on Linux, `/usr/local/lib/sitelock` on macOS,
`%ProgramData%\SiteBlock` on Windows) and points startup there. It does not
point to the build jar because `mvn clean` deletes `target/` — and an autostart
entry pointing at nothing enters a crash loop, so block deadlines stop being
enforced with no message explaining why.

After recompiling, run `enable` again to update the copy:

```bash
mvn package
sudo siteblock daemon enable
```

If the copy disappears, `daemon status` says so explicitly and tells you how
to fix it.

DNS mode is separate: it runs a server that answers on your IP, and devices
on the network need to point to it. It covers more devices than hosts, at the
cost of configuring each device.

The server handles **UDP and TCP on the same port**. TCP is not just for show:
when the answer does not fit in a UDP packet, the server returns `TC=1`
and the client retries the same query over TCP (RFC 7766). Without the TCP
listener, resolution fails precisely on large answers. And if the upstream
also responds truncated, SiteBlock retries the query over TCP before
delivering the answer.

Who can query the server is decided at startup. By default, only clients on
the local network (loopback, link-local, RFC1918 ranges, IPv6 ULA, and the
machine's own addresses) are allowed; everything else is silently refused. To allow
additional sources — a phone on another network, a company server — use
`--allow-from` with IPs or CIDR ranges, separated by commas:

```bash
sudo siteblock dns start --allow-from 192.168.1.50,10.8.0.0/24
```

The list is validated before the server starts: a typo shows up immediately,
not later.

## Development

```bash
mvn test          # tests (run on Linux, macOS, and Windows in CI)
mvn package       # produces the jar
```

The repository includes `./mvnw` (and `mvnw.cmd` on Windows) with Maven 3.9.16
pinned: that is what CI uses, and it avoids the "works on my machine" problem
with Maven and plugin versions.

Three Maven modules:

- `sitelock-core` — state, hosts file, domains
- `sitelock-dns` — local DNS server

The `sitelock-app` module builds the CLI and the executable jar. `sitelock-dns`
is optional and does not need to be built to use hosts mode.
