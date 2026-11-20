<p align="center"><img src="docs/promo/logo/hycon-emblem-256.png" width="128" alt="Logo di Hycon"></p>

# Hycon

🌐 [English](README.md) · [Deutsch](README.de.md) · [Español](README.es.md) · [Français](README.fr.md) · **Italiano** · [Nederlands](README.nl.md) · [Polski](README.pl.md) · [Português](README.pt.md) · [Русский](README.ru.md) · [Türkçe](README.tr.md)

[![Entra nel Discord](https://img.shields.io/badge/Discord-Crocell's%20Workshop-5865F2?logo=discord&logoColor=white)](https://discord.gg/maZTJbSs2h)

![Hycon: gestisci il tuo server da ovunque](docs/promo/hycon-banner.png)

Un pannello di amministrazione web (stile RCON) per server Hytale, che gira come plugin del server. Aprilo nel browser
sulla stessa macchina: vedi chi è online, espelli, banna e sbanna (a tempo o per sempre, con un motivo), esegui comandi
da console, segui il log dal vivo e guarda le dashboard aggiunte da altri plugin. Il pannello funziona anche sul telefono.

**Server in affitto?** Metti il pannello sul tuo dominio (`https://panel.tuodominio.it`) e aprilo da qualsiasi browser:
[docs/WEB-PANEL.md](docs/WEB-PANEL.md) (in inglese).

**Guida completa: [docs/GUIDE.md](docs/GUIDE.md)** (in inglese: installazione, primo accesso, ogni scheda, account dello
staff, tutte le impostazioni, accesso remoto via HTTPS, risoluzione dei problemi). Il pannello è disponibile in 10 lingue;
sceglila in alto a destra.

## Screenshot

| | |
|---|---|
| ![Statistiche](docs/promo/hycon-analytics.png) | ![Globo dei giocatori](docs/promo/hycon-globe.png) |
| ![Profili dei giocatori](docs/promo/hycon-profile.png) | ![Console dal vivo](docs/promo/hycon-console.png) |
| ![Moderazione](docs/promo/hycon-players.png) | ![Prestazioni](docs/promo/hycon-performance.png) |
| ![Sul telefono](docs/promo/hycon-phone.png) | |

## Funzioni

- **Giocatori**: chi è online e dove, più un elenco ricercabile di tutti quelli che sono mai entrati.
- **Moderazione**: avvisare, espellere, bannare (a tempo o per sempre, con motivo) e sbannare. Ogni giocatore ha una
  **fedina** di avvisi, espulsioni, ban e note dello staff.
- **Profili**: tempo di gioco, sessioni, attività per giorno e per ora, uccisioni e morti PvP, fedina e sezioni di altri plugin.
- **Punteggio di rischio**: ogni profilo ha un valore da 0 a 100 basato su avvisi, espulsioni, ban, segnalazioni,
  connessioni condivise e account nuovi. Ogni punto è elencato con il suo motivo. Le voci vecchie pesano sempre meno, e i
  moderatori possono usare **Azzera rischio** per i falsi allarmi.
- **Segnalazioni e multi-account**: segnalazioni dei giocatori da gestire e account collegati dalla connessione.
- **Segnalazioni di bug**: i giocatori le inviano in gioco con `/bug`; lo staff le legge e le chiude nel pannello.
- **Cronologia della chat**: una scheda Chat con tutto ciò che i giocatori hanno scritto (ricerca, 14 giorni).
- **Console**: il log del server dal vivo e comandi per gli admin (con un elenco di comandi bloccati).
- **Statistiche**: numero di giocatori, ping, attività e dashboard di altri plugin.
- **Profiler delle prestazioni**: sempre attivo e leggero (ben sotto l'1 %). Mostra quali plugin usano più tempo del
  server, ogni picco di lag con la sua causa e i plugin che bloccano il mondo con file o database. `/lag` in gioco,
  avvisi allo staff e su Discord.
- **Globo dei giocatori**: un globo 3D con un punto per giocatore (colorato in base al ping); i profili avvisano quando
  il ping è troppo basso per la distanza (probabilmente una VPN).
- **Pannello sul tuo dominio**: raggiungibile via HTTPS da ovunque, anche con un server in affitto.
- **Ban condivisi** tra server e un **registro di controllo** completo delle azioni dello staff.
- **Ricordami**: accesso automatico per 30 giorni, anche dopo il riavvio del server.
- **Sempre aggiornato**: le pagine aperte si aggiornano da sole e si ricaricano quando Hycon viene aggiornato.

## Installazione

Metti `Hycon-x.y.z.jar` nella cartella `mods` del server e avvialo. Hycon crea `hycon.json` (impostazioni commentate) e
l'account predefinito:

| Account | Password |
|---------|----------|
| `root`  | `hycon`  |

Apri `http://127.0.0.1:8096/` **sulla macchina del server**, accedi e scegli una nuova password; prima il pannello non
permette altro. Finché non la cambi, la password predefinita funziona solo dalla macchina del server. Poi crea gli
account dello staff nella scheda **Accounts**.

Bloccato fuori? Nella console del server: `/hycon resetpassword <nome>` scrive una nuova password in `FIRST-LOGIN.txt`.

## Aggiornamenti

Hycon controlla all'avvio e ogni 6 ore se c'è una nuova versione su GitHub. Se c'è, lo segnala in console, e gli admin
(operatori o chi ha `hycon.updates.notify`) ricevono il link in chat quando entrano.

## Ban condivisi

I proprietari possono condividere le liste dei ban tra server con Hycon (per esempio una lista per tutta una rete). Ogni
server crea un codice per gli altri; chi segue un codice importa i ban di quel server ogni 2 minuti e li revoca quando
lì vengono revocati. I ban locali non vengono mai toccati e i codici non passano mai in rete.

## Livelli degli account

| Livello   | Può                                                            |
|-----------|----------------------------------------------------------------|
| VIEWER    | vedere giocatori, ban e dashboard dei plugin                   |
| MODERATOR | + espellere, bannare, sbannare, leggere la console dal vivo    |
| ADMIN     | + eseguire comandi da console, leggere il registro di controllo|
| OWNER     | + gestire gli account del pannello, scaricare i log            |

## Sicurezza

- Di default il pannello ascolta **solo su 127.0.0.1**. Per accedervi dall'esterno, mettici davanti un reverse proxy
  HTTPS (vedi [docs/WEB-PANEL.md](docs/WEB-PANEL.md)).
- Le password sono salvate come hash PBKDF2-SHA256 (600.000 iterazioni).
- Le sessioni usano cookie HttpOnly e SameSite=Strict, ogni POST richiede un token CSRF e gli accessi falliti sono limitati.
- Ogni accesso, espulsione, ban, comando e modifica degli account finisce in `audit.log`.
- `BlockedCommands` in `hycon.json` elenca i comandi che il pannello rifiuta anche agli ADMIN.

I ban usano la lista di Hytale (`bans.json`, come `/ban`), quindi è il server ad applicarli.

## Per gli sviluppatori di plugin

Schede, sezioni del profilo, moduli e motivi di rischio: vedi il
[README in inglese](README.md#for-plugin-developers-adding-a-dashboard) e [docs/ADDING-A-PANEL.md](docs/ADDING-A-PANEL.md).
