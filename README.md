# kitly-cal

Eigener Termin-Dienst als Ersatz für cal.eu. Er bildet genau die Teile von cal.eu nach, die die Website `dsb-new` heute im Code aufruft, und nichts darüber hinaus.

Der Dienst spricht dasselbe Protokoll wie die Cal-API v2. Die App bleibt deshalb unverändert: Umgeschaltet wird nur über ihre vier `CALCOM_*`-Variablen, und genauso schnell wieder zurück.

**Stand:** Der Kern ist gebaut und getestet. Google Kalender ist angebunden: Termine dort blockieren Slots, bestätigte Buchungen landen als Termin mit eigenem Google-Meet-Link im Kalender, und Google schickt dem Kunden die Einladung per E-Mail. Was vor dem Umschalten noch offen ist, steht in [RISIKEN.md](./RISIKEN.md).

Technik: Spring Boot 4.1, Java 17, Maven, PostgreSQL mit Flyway. Kein Frontend.

## Was ersetzt wird

| Aufruf in dsb-new | Stelle im Code | In kitly-cal |
| --- | --- | --- |
| `GET /v2/slots` | `app/api/cal/slots/route.ts` | ja |
| `POST /v2/bookings` (Reservierung, `pending`) | `lib/cal-booking.ts` → `createPendingBooking` | ja |
| `POST /v2/bookings/{uid}/confirm` | `confirmBooking` | ja |
| `POST /v2/bookings/{uid}/decline` | `releaseBooking` | ja |
| `GET /v2/bookings?status=unconfirmed&take&skip` | `listPendingBookings` (Housekeeping) | ja |
| Webhook `BOOKING_CREATED` mit `x-cal-signature-256` | `app/api/cal/webhook/route.ts` | ja |
| Webhook `PING` | dieselbe Route | ja, über `POST /internal/webhook/ping` |

Nicht nachgebaut, weil die App es nicht aufruft: Stornieren, Umbuchen, Event-Typen und Verfügbarkeit per API pflegen, Teams, wiederkehrende Termine, Sitzplätze, die öffentliche Buchungsseite, das Embed.

## Ablauf

```
Fragebogen ─► Terminwahl ─────────────► Zahlung ─────────► bestätigt
              GET  /v2/slots            (Stripe/Kitly)     POST /v2/bookings/{uid}/confirm
              POST /v2/bookings                              └► Webhook BOOKING_CREATED ─► Kundenbereich
              = pending, Slot belegt

Housekeeping (Cron der App): GET /v2/bookings?status=unconfirmed ─► POST …/decline für abgelaufene
```

Eine Buchung kennt drei Zustände: `pending` (reserviert, unbezahlt), `accepted` (bestätigt), `rejected` (freigegeben). `pending` und `accepted` belegen den Slot. Nach außen passiert erst beim Bestätigen etwas.

## Schnellstart

```bash
cp .env.example .env                                  # API-Schlüssel eintragen
cp config/kitly-cal.example.yml config/kitly-cal.yml  # Event-Typ und Arbeitszeiten eintragen
docker compose up --build                             # PostgreSQL + Dienst auf http://localhost:8081
```

Ohne Docker, zum Ausprobieren im Arbeitsspeicher:

```bash
cd backend
mvn -DskipTests package
SPRING_PROFILES_ACTIVE=mock java -jar target/kitly-cal-backend.jar   # http://localhost:8080
```

Der Dienst startet nicht, wenn die Konfiguration fehlerhaft ist, und nennt alle Fehler auf einmal.

## Aufbau

```
backend/src/main/java/de/atstck/kitly/cal/
  availability/   freie Slots berechnen (reine Funktion), Anschluss für einen fremden Kalender
  service/        Anwendungsfälle: Slots, reservieren, bestätigen, freigeben, auflisten
  store/          Speicher-Schnittstelle, PostgreSQL-Speicher, In-Memory-Speicher (Tests, Mock)
  outbox/         Webhook, Mail, Kalendereintrag mit Wiederholung zustellen; Cal-Formate, Signatur
  web/            HTTP-Schnittstelle, API-Schlüssel, Fehlerformat
  config/         Konfiguration prüfen, Verdrahtung
  mock/           Profil „mock“: Attrappen und Test-Steuerung
backend/src/main/resources/db/migration/   Datenbankschema (Flyway)
config/           Event-Typen und Verfügbarkeit
contract/dsb-new/ Vertragstest, der in dsb-new läuft
scripts/smoke.sh  Prüfung eines laufenden Dienstes
```

Gegen Doppelbuchungen schützt die Datenbank selbst: Zwei blockierende Buchungen mit überlappendem Zeitraum lässt PostgreSQL nicht zu (Exclusion Constraint). Die Verfügbarkeitsprüfung davor ist nur die freundliche Fehlermeldung.

Der Datenbankzugriff läuft über `JdbcClient` mit ausgeschriebenem SQL statt über JPA wie in Kitly. Grund: Die drei kritischen Stellen (Sperrregel, Statuswechsel nur aus dem erwarteten Zustand, Outbox mit `skip locked`) sollen im Code genau so stehen, wie die Datenbank sie ausführt.

## Konfiguration

Fachliches steht in `config/kitly-cal.yml`, Vorlage: [`config/kitly-cal.example.yml`](./config/kitly-cal.example.yml). **Die Werte dort sind Platzhalter.** Arbeitszeiten, Vorlaufzeit, Puffer und Raster müssen aus dem cal.eu-Event-Typ übernommen werden. Die Datei wird aus `./config/` (lokaler Start) oder `/config/` (Container) gelesen; ohne Datei-Mount geht dieselbe Struktur als JSON in `SPRING_APPLICATION_JSON`.

| Feld | Bedeutung |
| --- | --- |
| `event-types[].id` | Wert für `CALCOM_EVENT_TYPE_ID` in der App |
| `duration-minutes` | Muss 120 sein, solange die App die Slot-Länge fest mit 2 Stunden annimmt (`lib/cal-slots.ts`) |
| `slot-interval-minutes` | Abstand zwischen zwei Slot-Starts, Standard = Dauer |
| `minimum-notice-minutes` | Frühester buchbarer Slot ab jetzt |
| `booking-window-days` | Wie weit im Voraus buchbar; weglassen = unbegrenzt |
| `buffer-before-minutes`, `buffer-after-minutes` | Mindestabstand zwischen zwei Buchungen = Summe beider Werte |
| `availability.weekly` | Zeitfenster je Wochentag in `time-zone` |
| `availability.date-overrides` | Ersetzt einen Tag; ohne `windows` ist er gesperrt (Urlaub, Feiertag) |
| `busy-time-failure-mode` | `closed`: bei nicht erreichbarem Kalender keine Slots anbieten; `open`: ohne Kalender weitermachen |

Geheimnisse und Adressen kommen aus der Umgebung, siehe [`.env.example`](./.env.example):

| Variable | Bedeutung |
| --- | --- |
| `SPRING_DATASOURCE_URL`, `…_USERNAME`, `…_PASSWORD` | PostgreSQL |
| `KITLY_CAL_API_KEYS` | Echte Schlüssel, kommagetrennt (mehrere erlauben Rotation), mindestens 24 Zeichen |
| `KITLY_CAL_SANDBOX_API_KEYS` | Test-Schlüssel, siehe unten |
| `KITLY_CAL_WEBHOOK_URL` | Ziel für `BOOKING_CREATED`, z. B. `https://www.digitalsmallbusiness.de/api/cal/webhook` |
| `KITLY_CAL_WEBHOOK_SECRET` | Derselbe Wert wie `CALCOM_WEBHOOK_SECRET` in der App |
| `KITLY_CAL_SANDBOX_WEBHOOK_URL` | Optionales Ziel für Test-Buchungen. Darf nicht die echte Adresse sein |
| `PORT` | Standard 8080 |

Die API muss unter dem Wurzelpfad ihres Hosts liegen. Die App baut ihre Adressen als `<CALCOM_API_BASE_URL>/v2/…`; ein Pfadanteil in der Basis-URL ginge dabei verloren.

## Google Kalender

Mit gesetztem `KITLY_CAL_GOOGLE_REFRESH_TOKEN` gilt:

- **Belegte Zeiten:** Jeder Termin in den Kalendern aus `KITLY_CAL_GOOGLE_BUSY_CALENDAR_IDS` (Standard: der Buchungskalender) blockiert Slots. Abgefragt wird die FreeBusy-API, die Antwort wird je Zeitraum 60 Sekunden zwischengespeichert. Ist ein Kalender nicht lesbar, greift `busy-time-failure-mode` (Standard `closed`: keine Slots statt Doppelbelegung).
- **Termin bei Bestätigung:** Der Dienst legt im Kalender `KITLY_CAL_GOOGLE_CALENDAR_ID` einen Termin an, mit dem Kunden als Gast und einem eigenen Google-Meet-Link. Google verschickt die Einladung an den Kunden (`send-updates: all`); sie enthält Datum, Meet-Link und „Zum Kalender hinzufügen“. Die Termin-ID wird aus der Buchungs-ID abgeleitet, eine Wiederholung nach Zeitüberschreitung erzeugt also keinen zweiten Termin.
- Test-Buchungen (Sandbox-Schlüssel) und das Mock-Profil berühren den Kalender nie.

Einrichtung, einmalig, mit dem Google-Konto, dessen Kalender gilt:

1. In der [Google Cloud Console](https://console.cloud.google.com/) ein Projekt anlegen (oder ein vorhandenes nehmen) und die **Google Calendar API** aktivieren.
2. **OAuth-Zustimmungsbildschirm:** Typ „Extern“, Scopes `https://www.googleapis.com/auth/calendar.events` und `https://www.googleapis.com/auth/calendar.freebusy`, eigenes Konto als Testnutzer. Danach auf **„In Produktion“** stellen; im Status „Test“ verfällt das Refresh-Token nach 7 Tagen. Eine Prüfung durch Google ist für die eigene Nutzung nicht nötig, beim Anmelden erscheint nur ein Warnhinweis.
3. **Anmeldedaten → OAuth-Client-ID**, Typ „Desktop-App“. Client-ID und Secret in `KITLY_CAL_GOOGLE_CLIENT_ID` und `KITLY_CAL_GOOGLE_CLIENT_SECRET`.
4. Refresh-Token holen, lokal:
   ```bash
   scripts/google-refresh-token.sh <Client-ID> <Client-Secret>
   ```
   Das Skript öffnet die Anmeldung, nimmt die Antwort auf `http://127.0.0.1:8765` entgegen und gibt das Token aus. Es gehört in `KITLY_CAL_GOOGLE_REFRESH_TOKEN`, nie ins Repo.
5. Optional `KITLY_CAL_GOOGLE_BUSY_CALENDAR_IDS`, wenn weitere Kalender (z. B. privat, geteilt) Slots blockieren sollen. Die IDs stehen in Google Kalender unter Einstellungen → Kalender → „Kalender-ID“.
6. Optional `KITLY_CAL_GOOGLE_SANDBOX_CALENDAR_ID`: ein eigener Testkalender im selben Google-Konto (z. B. „Kitly-cal Test“). Buchungen mit einem Sandbox-Schlüssel landen dann dort, mit „[TEST]“ im Titel und Meet-Link, nie im echten Kalender. Google verschickt dafür standardmäßig keine Einladung (`KITLY_CAL_GOOGLE_SANDBOX_SEND_UPDATES=none`, mit `all` lässt sich auch die Einladung testen). Der Testkalender muss von `KITLY_CAL_GOOGLE_CALENDAR_ID` verschieden sein; Mails des Dienstes und der Live-Webhook bleiben für Sandbox-Buchungen aus.

Beim Start meldet der Dienst `Google Calendar connected`. Fehlen Client-ID oder Secret, startet er nicht.

## Docker

| Datei | Zweck |
| --- | --- |
| `backend/Dockerfile` | Baut das Jar und ein schlankes Laufzeit-Image (Java 17, läuft nicht als root) |
| `docker-compose.yml` | Lokal: PostgreSQL und Dienst. Mit `--profile mock` zusätzlich der Mock ohne Datenbank |
| `docker-compose-production.yml` | Produktion nach dem Muster von `kitly`: externe Datenbank, Netzwerk `auto-proxy`, `VIRTUAL_HOST` |

```bash
docker compose up --build                                   # Dienst auf :8081, Datenbank auf :5433
docker compose --profile mock up --build mock               # Mock auf :4010
docker compose -f docker-compose-production.yml up -d --build
```

Soll der Dienst sich eine Datenbank mit anderen teilen (z. B. Supabase), bekommt er über `KITLY_CAL_DB_SCHEMA` ein eigenes Schema; Flyway legt es an.

## Testen ohne echte Buchung

**1. Automatische Tests.** `cd backend && mvn test` fährt 97 Tests. 80 davon brauchen nichts. Die übrigen 17 laufen gegen PostgreSQL, entweder über Testcontainers, wenn Docker läuft, oder gegen eine vorhandene Datenbank:

```bash
docker compose up -d db
cd backend && KITLY_CAL_TEST_DB_URL=jdbc:postgresql://localhost:5433/kitlycal mvn test
```

Die Tests arbeiten nur im Schema `kitly_cal_test`, das sie selbst anlegen und leeren. Ohne Datenbank werden sie übersprungen.

**2. Mock-Profil.** Der komplette Dienst, aber alles im Arbeitsspeicher und jede Außenwirkung durch eine Attrappe ersetzt. Ersetzt cal.eu beim Entwickeln:

```bash
docker compose --profile mock up --build mock
# in .env.local von dsb-new:
#   CALCOM_API_BASE_URL=http://localhost:4010
#   CALCOM_API_KEY=kcal_live_mock_0123456789abcdef     (Sandbox: kcal_test_mock_0123456789abcdef)
#   CALCOM_EVENT_TYPE_ID=1
```

Test-Steuerung, nur in diesem Profil und ohne Schlüssel:

| Aufruf | Wirkung |
| --- | --- |
| `GET /_mock/state` | Buchungen, Outbox, aufgezeichnete Webhooks, Mails und Kalendereinträge |
| `POST /_mock/clock {"advanceMinutes":200}` | Uhr vorstellen, z. B. über die Haltefrist |
| `POST /_mock/clock {"now":"2026-10-05T07:00:00Z","freeze":true}` | Uhr auf einen Zeitpunkt stellen und anhalten |
| `POST /_mock/busy {"start":"…","end":"…"}` | Fremden Kalendertermin simulieren |
| `POST /_mock/reset` | Alles leeren |

Webhooks werden nur aufgezeichnet. Mit `KITLY_CAL_MOCK_WEBHOOK_URL=http://host.docker.internal:3000/api/cal/webhook` und `KITLY_CAL_MOCK_FORWARD_WEBHOOKS=true` gehen sie signiert an eine lokal laufende App. Achtung: Die App verarbeitet sie dann echt, also mit Airtable und Kitly, sofern deren Schlüssel gesetzt sind.

**3. Sandbox-Schlüssel am echten Dienst.** Ein Schlüssel aus `KITLY_CAL_SANDBOX_API_KEYS` arbeitet in einer getrennten Welt: gleiche Regeln, gleiche Datenbank, aber Test-Buchungen belegen keine echten Slots, sind mit echten Schlüsseln unsichtbar und lösen weder Mail noch Kalendereintrag noch den echten Webhook aus.

```bash
scripts/smoke.sh https://termine.example.de <Sandbox-Schlüssel>            # reservieren, prüfen, freigeben
scripts/smoke.sh https://termine.example.de <Sandbox-Schlüssel> --confirm  # zusätzlich bestätigen
```

Mit einem echten Schlüssel verweigert das Skript die Arbeit. `--allow-live` hält einen echten Slot für wenige Sekunden und gibt ihn wieder frei; bestätigt wird damit nie. Das Skript braucht `curl` und `jq`.

**4. Vertragstest mit der App.** [`contract/dsb-new/kitly-cal-contract.test.ts`](./contract/dsb-new/kitly-cal-contract.test.ts) gehört nach `dsb-new/tests/`. Er lässt den bestehenden Cal-Client der App unverändert und über echtes HTTP gegen einen laufenden kitly-cal im Mock-Profil laufen, vom Slot-Abruf bis zum Webhook in die echte Route `/api/cal/webhook`. Start und Aufruf stehen im Kopf der Datei. Schlägt er fehl, ist kitly-cal kein Ersatz für cal.eu mehr.

## App umschalten

Kein Code, nur Umgebungsvariablen in Vercel:

```bash
CALCOM_API_BASE_URL=https://<Host von kitly-cal>
CALCOM_API_KEY=<Schlüssel aus KITLY_CAL_API_KEYS>
CALCOM_EVENT_TYPE_ID=<event-types[].id>
CALCOM_WEBHOOK_SECRET=<KITLY_CAL_WEBHOOK_SECRET>
```

Zurück zu cal.eu: die alten vier Werte wieder eintragen. Die Reihenfolge und die Fallen beim Umschalten stehen in [RISIKEN.md](./RISIKEN.md#umschalten).

## Betrieb

- `GET /health` prüft Dienst und Datenbank, ohne Schlüssel.
- `POST /internal/webhook/ping` schickt ein signiertes `PING` an die App. Nach jedem Deploy einmal aufrufen: Es prüft Adresse, Secret und dass keine Weiterleitung dazwischen liegt.
- `GET /internal/outbox?status=dead` listet Zustellungen, die endgültig gescheitert sind. Jede davon ist ein bestätigter Kunde, bei dem etwas fehlt. Dazu loggt der Dienst `outbox event is dead` auf Stufe `ERROR`.
- `POST /internal/outbox/run` stößt die Zustellung von Hand an.
- Webhooks werden bis zu sechsmal versucht (sofort, dann nach 30 s, 2 min, 10 min, 1 h, 6 h).
- Logs enthalten Buchungs-IDs, keine Namen und keine E-Mail-Adressen.
- Beim Start warnt der Dienst, solange Kalenderabgleich oder Kalendereintrag nicht angeschlossen sind.

## Bewusste Abweichungen von cal.eu

- **Jede Buchung startet als `pending`.** Der Schalter „Bestätigung erforderlich“ entfällt, ebenso der `PATCH` auf den Event-Typ aus der README von dsb-new.
- **Erneutes Bestätigen ist erlaubt** und wiederholt nichts. Lädt ein Kunde `/zahlung/success` neu, bekommt er so keinen Fehler und keine zweite Mail.
- **Keine Mail bei Reservierung und Freigabe.** cal.eu verschickt bei bestätigungspflichtigen Buchungen üblicherweise eigene Mails; der Dienst schweigt, bis bestätigt wird.
- **Webhooks werden wiederholt.** Der Empfänger kann dasselbe Ereignis zweimal sehen; der Header `x-kitly-cal-delivery-id` bleibt dabei gleich.
- **Weiterleitungen werden nicht verfolgt.** Antwortet der Empfänger mit 3xx, gilt das als Fehler mit Hinweis auf die Zieladresse.
- **`status=unconfirmed` listet alle offenen Reservierungen**, auch solche, deren Termin schon vorbei ist, damit das Housekeeping sie aufräumt.
