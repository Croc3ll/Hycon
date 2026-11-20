<p align="center"><img src="docs/promo/logo/hycon-emblem-256.png" width="128" alt="Logo Hycon"></p>

# Hycon

🌐 [English](README.md) · [Deutsch](README.de.md) · [Español](README.es.md) · [Français](README.fr.md) · [Italiano](README.it.md) · [Nederlands](README.nl.md) · **Polski** · [Português](README.pt.md) · [Русский](README.ru.md) · [Türkçe](README.tr.md)

[![Dołącz do Discorda](https://img.shields.io/badge/Discord-Crocell's%20Workshop-5865F2?logo=discord&logoColor=white)](https://discord.gg/maZTJbSs2h)

![Hycon: zarządzaj serwerem z dowolnego miejsca](docs/promo/hycon-banner.png)

Webowy panel administracyjny (w stylu RCON) dla serwerów Hytale, działający jako plugin serwera. Otwórz go w przeglądarce
na tym samym komputerze: zobacz, kto jest online, wyrzucaj, banuj i odbanowuj (czasowo lub na stałe, z powodem),
wykonuj polecenia konsoli, śledź log na żywo i przeglądaj panele dodane przez inne pluginy. Panel działa też na telefonie.

**Wynajęty serwer?** Umieść panel we własnej domenie (`https://panel.twojadomena.pl`) i otwieraj go w dowolnej
przeglądarce: [docs/WEB-PANEL.md](docs/WEB-PANEL.md) (po angielsku).

**Pełny poradnik: [docs/GUIDE.md](docs/GUIDE.md)** (po angielsku: instalacja, pierwsze logowanie, każda zakładka,
konta administracji, wszystkie ustawienia, zdalny dostęp przez HTTPS, rozwiązywanie problemów). Sam panel jest dostępny
w 10 językach; wybierz język w prawym górnym rogu.

## Zrzuty ekranu

| | |
|---|---|
| ![Statystyki](docs/promo/hycon-analytics.png) | ![Globus graczy](docs/promo/hycon-globe.png) |
| ![Profile graczy](docs/promo/hycon-profile.png) | ![Konsola na żywo](docs/promo/hycon-console.png) |
| ![Moderacja](docs/promo/hycon-players.png) | ![Wydajność](docs/promo/hycon-performance.png) |
| ![Na telefonie](docs/promo/hycon-phone.png) | |

## Funkcje

- **Gracze**: kto jest online i gdzie, plus przeszukiwalna lista wszystkich, którzy kiedykolwiek dołączyli.
- **Moderacja**: ostrzeżenia, wyrzucenia, bany (czasowe lub stałe, z powodem) i odbanowania. Każdy gracz ma **kartotekę**
  ostrzeżeń, wyrzuceń, banów i notatek administracji.
- **Profile**: czas gry, sesje, aktywność według dni i godzin, zabójstwa i śmierci PvP, kartoteka i sekcje innych pluginów.
- **Ocena ryzyka**: każdy profil ma wynik od 0 do 100 liczony z ostrzeżeń, wyrzuceń, banów, zgłoszeń, wspólnych
  połączeń i nowych kont. Każdy punkt ma podany powód. Stare wpisy z czasem tracą wagę, a moderatorzy mogą użyć
  **Wyczyść ryzyko** przy fałszywych alarmach.
- **Zgłoszenia i multikonta**: zgłoszenia graczy do rozpatrzenia i konta powiązane przez połączenie.
- **Zgłoszenia błędów**: gracze wysyłają je w grze przez `/bug`; administracja czyta je i zamyka w panelu.
- **Historia czatu**: zakładka Chat ze wszystkim, co napisali gracze (wyszukiwanie, 14 dni).
- **Konsola**: log serwera na żywo i polecenia dla adminów (z listą zablokowanych poleceń).
- **Statystyki**: liczba graczy, ping, aktywność i panele innych pluginów.
- **Profiler wydajności**: zawsze włączony i lekki (znacznie poniżej 1 %). Pokazuje, które pluginy zużywają najwięcej
  czasu serwera, każdy skok lagów z przyczyną i pluginy blokujące świat operacjami na plikach lub bazie danych. `/lag`
  w grze, alerty dla administracji i na Discorda.
- **Globus graczy**: globus 3D z kropką dla każdego gracza (kolor według pingu); profile ostrzegają, gdy ping jest zbyt
  niski jak na odległość (prawdopodobnie VPN).
- **Panel we własnej domenie**: dostępny przez HTTPS z każdego miejsca, nawet przy wynajętym serwerze.
- **Wspólne bany** między serwerami i pełny **dziennik audytu** działań administracji.
- **Zapamiętaj mnie**: automatyczne logowanie przez 30 dni, także po restarcie serwera.
- **Zawsze aktualny**: otwarte strony same się odświeżają i przeładowują po aktualizacji Hycona.

## Instalacja

Wrzuć `Hycon-x.y.z.jar` do folderu `mods` serwera i uruchom go. Hycon utworzy `hycon.json` (ustawienia z komentarzami) i
domyślne konto:

| Konto  | Hasło   |
|--------|---------|
| `root` | `hycon` |

Otwórz `http://127.0.0.1:8096/` **na komputerze serwera**, zaloguj się i ustaw nowe hasło; wcześniej panel nie pozwala
na nic innego. Dopóki go nie zmienisz, domyślne hasło działa tylko z komputera serwera. Potem utwórz konta dla
administracji w zakładce **Accounts**.

Brak dostępu? W konsoli serwera: `/hycon resetpassword <nazwa>` zapisze nowe hasło w `FIRST-LOGIN.txt`.

## Aktualizacje

Hycon sprawdza przy starcie i co 6 godzin, czy na GitHubie jest nowsza wersja. Jeśli tak, pokazuje ostrzeżenie w
konsoli, a admini (operatorzy lub osoby z `hycon.updates.notify`) dostają link na czacie po wejściu na serwer.

## Wspólne bany

Właściciele mogą udostępniać listy banów między serwerami z Hyconem (na przykład jedną listę dla całej sieci). Każdy
serwer tworzy kod dla pozostałych; serwer, który śledzi kod, co 2 minuty pobiera bany tamtego serwera i zdejmuje je, gdy
zostaną tam zdjęte. Lokalne bany nigdy nie są ruszane, a kody nigdy nie są wysyłane przez sieć.

## Poziomy kont

| Poziom    | Może                                                           |
|-----------|----------------------------------------------------------------|
| VIEWER    | przeglądać graczy, bany i panele pluginów                      |
| MODERATOR | + wyrzucać, banować, odbanowywać, czytać konsolę na żywo       |
| ADMIN     | + wykonywać polecenia konsoli, czytać dziennik audytu          |
| OWNER     | + zarządzać kontami panelu, pobierać logi                      |

## Bezpieczeństwo

- Domyślnie panel nasłuchuje **tylko na 127.0.0.1**. Aby mieć dostęp z zewnątrz, postaw przed nim reverse proxy z HTTPS
  (zobacz [docs/WEB-PANEL.md](docs/WEB-PANEL.md)).
- Hasła są zapisywane jako skróty PBKDF2-SHA256 (600 000 rund).
- Sesje używają ciasteczek HttpOnly i SameSite=Strict, każdy POST wymaga tokenu CSRF, a nieudane logowania są ograniczane.
- Każde logowanie, wyrzucenie, ban, polecenie i zmiana konta trafia do `audit.log`.
- `BlockedCommands` w `hycon.json` to lista poleceń, których panel odmawia nawet ADMIN-om.

Bany korzystają z własnej listy Hytale (`bans.json`, jak `/ban`), więc egzekwuje je serwer.

## Dla twórców pluginów

Własne zakładki, sekcje profilu, formularze i powody ryzyka: zobacz
[angielskie README](README.md#for-plugin-developers-adding-a-dashboard) i [docs/ADDING-A-PANEL.md](docs/ADDING-A-PANEL.md).
