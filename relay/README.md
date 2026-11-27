# Pairline relay: your own server

Pairline's Cloud mode lets your iPhone use your Android phone's SIM while the two are apart. Texts and one-time codes
always work through your own iCloud. **Calls** need a live connection between the phones, and normally that goes
straight to your home: your router has to let one connection in (UPnP, NAT-PMP or port forwarding).

Many homes can't do that. Most 4G/5G home routers, and many fiber and cable providers, put you behind
**carrier-grade NAT**: one internet address shared by many homes, so nothing can reach yours from outside, whatever
your router's settings. If Pairline says "carrier-grade NAT" or "the router doesn't open a port", this relay is the
way around it.

You run it on a server of your own. Both phones connect **out** to it, and it joins the two connections:

```
 iPhone (anywhere) ──TLS──►  your server  ◄──TLS── Android phone (at home)
                       joins the two connections
```

No port forwarding, UPnP, public address or DNS name is needed at home.

## What your server can and can't see

- Everything between your phones is **encrypted end to end** with keys only your two phones hold: the same session
  as Pairline's direct connection. The relay passes the bytes along and can't read or change them. It doesn't even
  parse them.
- It sees **both phones' IP addresses**, and when and how much they talk. So does your hosting provider.
- It keeps nothing on disk but its own key, certificate and access key, and its log has counts only: no addresses,
  keys or phone ids.
- Texts, codes and the notifications that wake your iPhone still come through your own iCloud: only Apple can wake
  a locked iPhone. The relay replaces your router, not iCloud.
- It's Pairline's own code (this directory, Go's standard library only, no other dependencies), so you can read all
  of it.

## What you need

- **Cloud mode** on in Pairline on your iPhone, with Pairline Bridge up to date on the Android phone.
- **A Linux server with a public internet address.** Any small virtual server (VPS) is plenty: 1 CPU and 512 MB of
  memory serve many phones. A Raspberry Pi or a NAS works too, but only if it has a public address (one at home behind
  the same carrier-grade NAT won't help).
- **Choose one near home.** Every word of a call goes to the server and back, so a server in your own country, or the
  nearest one, keeps the delay small. A server on another continent adds a noticeable lag.
- **Traffic:** a call uses about 30 kbit/s each way, about 30 MB an hour in total. While no session runs, the bridge's
  waiting connection costs about 0.4 MB a day.
- **One open TCP port**, 443 by default (it looks like ordinary HTTPS to networks that block other ports).

## Quick start with Docker

1. Install Docker on the server ([docs.docker.com/engine/install](https://docs.docker.com/engine/install/)).
2. Start the relay. Put your server's internet address (or a DNS name pointing at it) after
   `PAIRLINE_RELAY_PUBLIC`:

   ```sh
   docker run -d --name pairline-relay --restart unless-stopped \
     -p 443:443 -v pairline-relay:/data \
     -e PAIRLINE_RELAY_PUBLIC=203.0.113.5 \
     ghcr.io/mirzaaghazadeh/pairline-relay
   ```

   The image is public (amd64 and arm64), built from this directory by GitHub Actions. To use your own build instead:
   `docker build -t pairline-relay relay/` and `pairline-relay` in place of `ghcr.io/mirzaaghazadeh/pairline-relay`.
3. Show its link and QR code:

   ```sh
   docker logs pairline-relay
   ```
4. On your iPhone: **Pairline › Settings › Connection › Advanced › Your own server**, tap **Scan QR code** and point the camera
   at the code in your terminal (or copy the `pairline-relay://` link and tap **Paste link**).
5. Within a few seconds, Pairline Bridge on the Android phone says it's connected to your server. Calls away from
   home now go through it whenever your router lets nothing in.

Open TCP port 443 in your server's firewall if it has one (`ufw allow 443/tcp`, or your provider's firewall page).

### With Docker Compose

[`docker-compose.yml`](docker-compose.yml) is the only file a server needs: it pulls the published image and runs it
with a health check. Copy it to the server, then:

```sh
RELAY_ADDRESS=203.0.113.5 docker compose up -d     # your server's address or DNS name
docker compose logs pairline-relay                  # the link and QR code
```

| Variable | Default | Meaning |
|----------|---------|---------|
| `RELAY_ADDRESS` | (required) | Your server's internet address or DNS name, as your phones reach it. An IPv6 address goes in brackets: `[2a01:4f8::1]` |
| `RELAY_PORT` | `47840` | The port your phones use (published to the relay's 443 inside the container). Use `443` if nothing else on the server needs it |
| `RELAY_QR` | `dark` | How the QR code is drawn in the log: `dark` for a dark log view (Coolify's, most terminals), `light` for a light one, `ansi` for any terminal (black on white with colour codes), `off` |
| `RELAY_IMAGE` | `ghcr.io/mirzaaghazadeh/pairline-relay:latest` | Another image, such as a pinned version (`…:0.1.1`) or your own build |

### On Coolify, with a domain

Tested on Coolify with a Cloudflare domain. The relay gets a port of its own (47840), published straight to the
server, and your domain only points at the server: Coolify's proxy isn't involved, so its **Domains** field stays
empty.

1. **DNS:** an `A` record for a name such as `relay.example.com` → the server's address. On Cloudflare, set it to
   **DNS only** (grey cloud). The orange-cloud proxy would put Cloudflare's certificate in front, which the phones
   refuse, and it doesn't carry port 47840 anyway. (An IP address instead of a name works too.)
2. **Coolify:** **+ New › Docker Compose Empty**, and paste [`docker-compose.yml`](docker-compose.yml).
3. Under **Environment Variables**, set `RELAY_ADDRESS` to that name (`relay.example.com`). Leave `RELAY_PORT` at
   47840 unless that port is taken.
4. **Leave the service's Domains field empty.** A domain there makes Coolify's proxy (Traefik) answer with a Let's
   Encrypt certificate and pass web requests along, which the relay doesn't speak: the phones would see "the server's
   certificate doesn't match".
5. **Deploy.** The status turns **healthy** once the relay answers its own health check.
6. Open TCP port 47840 in the server's firewall if it has one (`ufw allow 47840/tcp`, or your provider's firewall page).
7. Open the service's **Logs**: the `pairline-relay://relay.example.com:47840?…` link is printed under the QR code.
   Scan the code with Pairline, or copy the link, send it to your iPhone (Notes, Messages to yourself, AirDrop) and use
   **Paste link**.

To show the link again later, redeploy or restart the service; the volume keeps the same key, so the link stays the
same.

**Check it from any computer:** `openssl s_client -connect relay.example.com:47840 -servername relay.example.com`
should show a certificate named `Pairline relay`. A `TRAEFIK DEFAULT CERT` or a Let's Encrypt one means the
connection went through a proxy instead (the Domains field, or Cloudflare's orange cloud).

### Sharing your server with a friend

One relay serves many pairs of phones: each iPhone picks its own random rendezvous id, so your sessions and your
friend's never meet, and neither can reach the other's phones (each pair's keys protect its own sessions). Send your
friend the link privately, as you would a password. They set it up on their own iPhone the same way, with their own
paired Android phone. If you stop sharing, `pairline-relay new-key` gives you a new link (and everyone scans the new
one).

### Port 443 is taken

If a web server already uses 443, pick another port and tell the relay which one the phones should use:

```sh
docker run -d --name pairline-relay --restart unless-stopped \
  -p 8443:443 -v pairline-relay:/data \
  -e PAIRLINE_RELAY_PUBLIC=203.0.113.5:8443 \
  ghcr.io/mirzaaghazadeh/pairline-relay
```

## Without Docker

Download `pairline-relay-linux-amd64` or `pairline-relay-linux-arm64` from a `relay-v*` release (or build it:
`CGO_ENABLED=0 go build -trimpath -o pairline-relay .` in this directory, with Go 1.24 or later), then:

```sh
sudo install -m 755 pairline-relay /usr/local/bin/
sudo install -m 644 pairline-relay.service /etc/systemd/system/
sudo systemctl edit pairline-relay
```

In the editor, add:

```ini
[Service]
Environment=PAIRLINE_RELAY_PUBLIC=203.0.113.5
```

Then:

```sh
sudo systemctl enable --now pairline-relay
journalctl -u pairline-relay        # the link and QR code
```

The unit runs the relay as its own throwaway user, keeps its key in `/var/lib/pairline-relay`, and gives it only the
right to listen on port 443.

To try it by hand: `PAIRLINE_RELAY_PUBLIC=203.0.113.5 PAIRLINE_RELAY_LISTEN=:8443 ./pairline-relay` (ports below
1024 need root or `CAP_NET_BIND_SERVICE`).

## Settings

| Variable | Default | Meaning |
|----------|---------|---------|
| `PAIRLINE_RELAY_PUBLIC` | found on the server's network card, if it has exactly one public address | The address or DNS name your phones use to reach the server, optionally with `:port`. Only used to print the link |
| `PAIRLINE_RELAY_LISTEN` | `:443` | Where the relay listens |
| `PAIRLINE_RELAY_DATA` | `/data` in Docker, `./pairline-relay-data` otherwise | Its key, certificate and access key |
| `PAIRLINE_RELAY_MAX_CONNECTIONS` | `1000` | Connections at once (each pair of phones uses one waiting connection, plus one more during a session) |
| `PAIRLINE_RELAY_QR` | `ansi` | How the QR code is drawn: `ansi` (black on white with colour codes, for a terminal), `dark` or `light` (plain text for a web log viewer with that background), `off` |
| `PAIRLINE_RELAY_BEHIND_PROXY` | `false` | `true` only behind a TCP proxy that passes the TLS through untouched (HAProxy, nginx `stream`, Traefik TCP passthrough): every connection then comes from the proxy, so the limits per address are off |

Commands: `pairline-relay link` prints the link and code again, `pairline-relay new-key` replaces the access key,
`pairline-relay healthcheck` exits 0 when the relay on this machine answers with its own certificate (Docker's health
check uses it: the image has no shell), and `pairline-relay version`. With Docker:
`docker exec pairline-relay /pairline-relay link`.

## Keep the link to yourself

The link holds the server's **access key**. Anyone who has it can use your relay to connect their own phones (never to
reach yours: your phones' keys protect your sessions). Don't post it or a screenshot of the code. If it got out:

```sh
docker exec pairline-relay /pairline-relay new-key
```

The running relay picks up the new key within 5 seconds, and links given out before stop working. Scan the new code on
your iPhone; Pairline Bridge then shows it's connected again.

The server's **certificate** is how your phones know they reached your relay and not an impostor: its fingerprint is
in the link, and the phones check it on every connection. It's made once and kept in the data volume. **Keep that
volume:** if it's deleted (`docker volume rm`, a new server), the relay makes a new certificate and you scan the new code.
No domain, Let's Encrypt or renewal is needed.

## Updating and removing

```sh
docker pull ghcr.io/mirzaaghazadeh/pairline-relay
docker rm -f pairline-relay
# run the same `docker run` command as before; the volume keeps the key, so the phones need nothing new
```

With Compose: `docker compose pull && docker compose up -d`. On Coolify: **Redeploy** (it pulls `latest` again).

To stop using it, remove it on the iPhone (Settings › Connection › Advanced › Your own server › Remove), then
`docker rm -f pairline-relay && docker volume rm pairline-relay` (with Compose: `docker compose down -v`).

## Troubleshooting

| Pairline says | What to check |
|---------------|---------------|
| Can't reach your server | The relay runs (`docker ps`), the port is open in the server's and the provider's firewall, and `PAIRLINE_RELAY_PUBLIC` is the address your phones can reach. From another network: `openssl s_client -connect 203.0.113.5:443` should show a certificate named "Pairline relay" |
| The server's certificate doesn't match | The relay made a new certificate (its data volume was lost or replaced). Scan its current code again |
| The server refused the key | The access key was replaced (`new-key`). Scan the current code again |
| The server is full | More connections than `PAIRLINE_RELAY_MAX_CONNECTIONS`; raise it |
| Connected, but calls still don't connect | The relay's log (`docker logs pairline-relay`) counts "found no bridge": the Android phone isn't connected to it right now. Check that it has internet and Pairline Bridge runs |

The relay's log, once a minute when something changed, says how many bridges are waiting and how many sessions run,
and counts wrong keys, malformed connections and connections turned away. It never logs addresses.

## For the curious

- Protocol: `docs/PROTOCOL.md`, "Your own server (relay, D-106)". Why it exists and why Go: `docs/DECISIONS.md`,
  D-106.
- Each phone sends a 54-byte preamble inside TLS (who it is, the access key, and a random rendezvous id the iPhone
  picked); the relay keeps the bridge waiting under that id and joins the iPhone to it.
- Limits against abuse: 10 seconds for TLS and the preamble, 60 new connections a minute per address (an IPv6 /64
  counts as one), an address that sends 10 wrong keys in 10 minutes is turned away for 10 minutes, and a joined
  connection with no traffic for 5 minutes is closed.
- Tests: `go test -race ./...`. `tools/qr-check.swift` checks the QR codes with Apple's own detector on a Mac.
- Releases: `relay-v*` tags build the binaries and the image in GitHub Actions, reproducibly, with signed build
  provenance (`gh attestation verify <file> --repo mirzaaghazadeh/pairline`).
