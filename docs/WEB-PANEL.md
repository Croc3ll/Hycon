# Putting the Hycon panel on your own domain

This guide is for owners who rent their server from a host and can't open
`http://127.0.0.1:8096/` on the server machine. When you're done, your staff open the panel at an
address like `https://panel.yourdomain.com` from any browser.

## First: what you can and can't upload

The panel isn't a set of files you upload to a website. It runs **inside your Hytale server**, as
part of the Hycon plugin, because it needs the live server to show players, run commands and ban.

So a normal web hosting plan (cPanel, "upload your files" hosting) **can't run it**. What your
domain does is point people to the panel. You need one of these:

| Your setup | Use |
|---|---|
| Your host lets you run programs on the server (shell, Docker, a VPS or dedicated box) | [Option A: Cloudflare Tunnel](#option-a-cloudflare-tunnel-recommended) |
| Your host only gives you a game panel and a file manager | [Option B: a small VPS in front](#option-b-a-small-vps-in-front) |
| You run the server on your own PC or VPS | [Option C: a reverse proxy on the same machine](#option-c-a-reverse-proxy-on-the-same-machine) |

## Step 1: log in for the first time without local access

The default `root` / `hycon` login only works from the server machine itself. From anywhere else,
make a new password instead:

1. In your host's server console, run `/hycon resetpassword root`.
2. Open the Hycon folder (`mods/Hycon_Hycon/`) in your host's file manager.
3. Open `FIRST-LOGIN.txt`. It holds the new password. Delete the file once you've read it.

You'll use this password once the panel is on your domain. The panel then asks you to choose your
own password.

## Option A: Cloudflare Tunnel (recommended)

Free, gives you HTTPS, and needs **no open ports**. Your domain must use Cloudflare for its DNS
(free plan is fine).

1. In the Cloudflare dashboard, open **Zero Trust** → **Networks** → **Tunnels** → **Create a
   tunnel**. Pick **Cloudflared** and give it a name like `hycon`.
2. Cloudflare shows an install command for your system. Run it on the **game server machine**.
3. Under **Public hostname**, add:
   - Subdomain: `panel`, Domain: `yourdomain.com`
   - Service: `HTTP`, URL: `127.0.0.1:8096`
4. In `mods/Hycon_Hycon/hycon.json`, set:

   ```json
   "AllowedHosts": ["panel.yourdomain.com"],
   "SecureCookie": true,
   "TrustProxy": true,
   ```

   Keep `BindAddress` at `127.0.0.1`: the tunnel reaches Hycon from the same machine.
5. Restart the server and open `https://panel.yourdomain.com`.

Extra protection (optional): in Zero Trust, add an **Access application** for
`panel.yourdomain.com` so only your staff's emails can even see the login page.

## Option B: a small VPS in front

For hosts that only give you a game panel. You rent a small VPS (a few dollars a month), point your
domain at it, and it passes visitors on to Hycon on your game server.

1. **Let Hycon listen beyond the server machine.** In `hycon.json`:

   ```json
   "BindAddress": "0.0.0.0",
   "AllowedHosts": ["panel.yourdomain.com"],
   "SecureCookie": true,
   "TrustProxy": true,
   ```

2. **Open port `8096`** in your host's panel (often under "Ports" or "Network"). If the host lets
   you limit who can connect, allow only your VPS's IP address.
3. **Point your domain at the VPS.** At your domain registrar, add an `A` record: name `panel`,
   value = your VPS's IP address.
4. **Install [Caddy](https://caddyserver.com) on the VPS.** It gets the HTTPS certificate by
   itself. Put this in `/etc/caddy/Caddyfile` (use your game server's IP):

   ```
   panel.yourdomain.com {
       reverse_proxy GAME.SERVER.IP:8096
   }
   ```

   Then run `systemctl reload caddy`.
5. Restart the game server and open `https://panel.yourdomain.com`.

> **Know the trade-off.** Visitors reach the VPS over HTTPS, but the hop from the VPS to your game
> server is plain HTTP. Limit port `8096` to your VPS's IP if your host allows it. If you can, ask
> your host whether you can run Cloudflare Tunnel (Option A): it has no plain hop.

## Option C: a reverse proxy on the same machine

If you run the server yourself, keep `BindAddress` at `127.0.0.1` and put Caddy or nginx on the
same machine. See [section 6 of the guide](GUIDE.md#6-opening-the-panel-from-another-computer)
for both configs.

## Check it worked

- The address shows a padlock (HTTPS) and the Hycon login page.
- You can log in with the password from `FIRST-LOGIN.txt`, and the panel asks for a new one.
- The Audit log tab lists your login with **your** IP address, not the proxy's. If it shows the
  proxy's, `TrustProxy` isn't set.

## Problems

| What you see | Fix |
|---|---|
| "Unknown host name; add it to AllowedHosts" | The address isn't in `AllowedHosts`. Add it exactly, without `https://`, and restart. |
| You log in, then get sent back to the login page | `SecureCookie` is `true` but the address isn't HTTPS. Open it with `https://`. |
| "Too many failed logins" for everyone at once | `TrustProxy` is off behind a proxy, so all visitors share the proxy's address. Turn it on. |
| The page doesn't load (Option B) | Port `8096` isn't open on your host, or the IP in the Caddyfile is wrong. |
| The default password doesn't work | That's expected away from the server machine. Use [Step 1](#step-1-log-in-for-the-first-time-without-local-access). |

## Never do this

- Don't open port `8096` to the internet **without** HTTPS in front. Passwords and session
  cookies would travel in plain text.
- Don't set `TrustProxy` to `true` unless a proxy you control sits in front. Otherwise anyone can
  fake their address and get around the login limits.
