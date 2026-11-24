# Risiken und Lücken

Stand: 24.11.2026, mit Google-Kalender-Anbindung. Sortiert nach Schwere. „Blocker“ heißt: vor dem Umschalten lösen, sonst merkt es der Kunde.

## Kurzfassung

kitly-cal ersetzt alle sechs Aufrufe, die die App `dsb-new` an cal.eu richtet, und ist dagegen getestet. cal.eu hat drei Dinge erledigt, die nie im Code der App standen. Seit der Google-Kalender-Anbindung (Entscheidung 24.11.2026: Google Kalender mit Meet-Link) übernimmt der Dienst sie, sobald die Google-Zugangsdaten gesetzt sind:

1. **Kalenderabgleich:** Termine in den konfigurierten Google-Kalendern blockieren Slots (L1).
2. **Kalendereintrag und Meeting-Link:** Jede bestätigte Buchung wird ein Termin mit eigenem Meet-Link (L3).
3. **Bestätigung an den Kunden:** Google verschickt die Einladung mit Datum und Meet-Link (L2, siehe dort zur Einschränkung).

Die Seite `/terminbuchung` verspricht dem Kunden genau das („Terminbestätigung per E-Mail“, „Kalendereintrag mit allen Details“, „Google Meet Link für die Session“). Ohne gesetztes Refresh-Token fehlt alles drei wieder.

Dazu kommt beim Umschalten: **Der Dienst weiß nichts von Terminen, die schon in cal.eu stehen.** Ohne Übernahme bietet er bereits verkaufte Slots erneut an.

## Befund in der App, unabhängig vom Dienst

**B1 – `/zahlung/success` bestätigt nach der Zahlung keine Buchung.** Beim Durchspielen des Ablaufs in dsb-new ist mir aufgefallen, dass die Seite Cookies setzt (`store.set(BOOKING_PAYMENT_COOKIE, …)`, `app/zahlung/success/page.tsx:59`). Next.js erlaubt das in einer Seite nicht, nur in Server Actions und Route Handlern. Der Aufruf wirft „Cookies can only be modified in a Server Action or Route Handler“, der umgebende `catch` leitet auf `/zahlung?status=fehler` um, und `confirmBooking` wird nie erreicht.

Nachgestellt habe ich das lokal mit Next 16.0.7 im Entwicklungsmodus, gültigen signierten Cookies und einem Kitly-Platzhalter, der „active“ meldet: Antwort 307 nach `/zahlung?status=fehler`, die Buchung blieb `pending`. Die Prüfung in Next (`phase === 'action'`) unterscheidet nicht zwischen Entwicklung und Produktion; in Produktion selbst habe ich es nicht gesehen.

Folge, falls es dort genauso ist: Der Kunde zahlt, sieht einen Fehler, die Reservierung verfällt nach der Haltefrist, und es entsteht weder Termin noch Kundenbereich-Zugang. Das gilt mit cal.eu genauso wie mit kitly-cal. `app/auth/verify/page.tsx:94` setzt auf dieselbe Weise ein Cookie; das habe ich nicht nachgestellt.

Ich habe es nicht geändert, weil es nicht zum Auftrag gehört. Die Behebung ist überschaubar: die Logik in einen Route Handler verlegen, der danach weiterleitet.

## Lücken: was cal.eu tat und der Dienst nicht tut

| # | Lücke | Schwere | Folge | Vorbereitet | Fehlt |
| --- | --- | --- | --- | --- | --- |
| L1 | Abgleich mit dem eigenen Kalender | Gelöst, sobald Google eingerichtet ist | – | `GoogleBusyTimes` (FreeBusy-API, 60 s Cache, bei Fehler `busy-time-failure-mode`), getestet gegen nachgebildete Google-API | Google-Zugangsdaten setzen (README, „Google Kalender“) und einmal mit echtem Kalender prüfen |
| L2 | Bestätigungs-Mail an Kunde und an dich | Mittel | Die Bestätigung an den Kunden ist die Google-Einladung („Einladung: … “) mit Meet-Link, kein eigener Mailtext mit deinem Branding. Du selbst siehst die Buchung als Termin in deinem Kalender. | Google-Einladung über `send-updates: all`; zusätzlich Schnittstelle `Notifier` mit ICS-Datei für eine eigene Mail | Nur falls eine eigene, gestaltete Bestätigungs-Mail gewünscht ist: Mail-Versand (z. B. über kitly-mail) und Mailtext |
| L3 | Kalendereintrag und Meeting-Link | Gelöst, sobald Google eingerichtet ist | – | `GoogleCalendarWriter`: Termin mit Kunde als Gast und eigenem Meet-Link, wiederholungsfest über die aus der Buchungs-ID abgeleitete Termin-ID | Google-Zugangsdaten setzen |
| L4 | Übernahme bestehender Termine | Niedrig | cal.eu hat seine Buchungen in deinen Google-Kalender geschrieben; über L1 blockieren sie Slots auch in kitly-cal. | L1 | Einmal prüfen, dass die zukünftigen cal.eu-Termine im Kalender stehen |
| L5 | Stornieren und Umbuchen | Hoch | Sagt ein Kunde ab, lässt sich der Slot nur per SQL freigeben. Der Kunde hat keinen Link dafür. Die App ruft das nie auf, deshalb gibt es keinen Endpunkt. | Zustandsmodell lässt sich erweitern | Endpunkt `cancel` und eine Möglichkeit für dich, ihn auszulösen |
| L6 | Oberfläche für dich | Mittel | Buchungen siehst du nur über `GET /v2/bookings`, die Datenbank oder Airtable. | Lese-Endpunkte, Outbox-Übersicht | Jede Art von Admin-Ansicht (Frontend war ausdrücklich nicht gefragt) |
| L7 | Erinnerungen vor dem Termin | Mittel | Falls in cal.eu Workflows oder Erinnerungen aktiv sind, entfallen sie. Ob welche aktiv sind, konnte ich nicht sehen. | – | Prüfen, dann ggf. bauen |
| L8 | Echte Verfügbarkeitsregeln | Blocker (Konfiguration) | `config/kitly-cal.example.yml` enthält geratene Werte (Mo–Do 9–17, Fr 9–13 Uhr, 24 h Vorlauf). Limits pro Tag oder Woche kennt der Dienst gar nicht. | Arbeitszeiten, Ausnahmetage, Vorlauf, Buchungsfenster, Puffer, Raster | Werte aus dem cal.eu-Event-Typ übertragen; Limits, falls genutzt |
| L9 | Mails bei Reservierung und Freigabe | Niedrig | cal.eu verschickt bei bestätigungspflichtigen Buchungen üblicherweise „wartet auf Bestätigung“ und „abgelehnt“. Ob das bei euch aktiv ist, weiß ich nicht. Der Dienst schweigt, was eher ein Gewinn ist, aber eine Änderung. | – | Entscheidung, ob ein Hinweis „Reservierung verfallen“ gewünscht ist |
| L10 | Ablauf unbezahlter Reservierungen im Dienst selbst | Mittel | Das Aufräumen liegt weiter allein beim Cron der App. Fällt er aus, bleiben Slots dauerhaft belegt. Das ist heute schon so. | Alles Nötige für den Cron | Eigene Ablaufzeit im Dienst als zweites Netz |

## Risiken

### Hoch

**R1 – Ausfall des Dienstes trifft zahlende Kunden.** Bisher trug cal.eu die Verfügbarkeit, jetzt ein einzelner Container mit einer Datenbank. Ist der Dienst während der Zahlung nicht erreichbar, schlägt `confirmBooking` fehl. Laut Code löscht `/zahlung/success` dann das Slot-Cookie und schickt den Kunden mit „Reservierung abgelaufen“ zurück zur Terminwahl, wo auch keine Slots laden (heute greift vorher B1). Der Kunde hat gezahlt und keinen Termin. Die Reservierung selbst bleibt erhalten, bis das Housekeeping sie abräumt. Gegenmittel: Überwachung von `/health`, `restart: unless-stopped` ist gesetzt, und in der App zwischen „Slot verloren“ (400) und „Dienst nicht erreichbar“ (Netzwerkfehler, 5xx) unterscheiden.

**R2 – Der Webhook-Empfänger der App ist nicht wiederholungsfest.** Der Dienst stellt „mindestens einmal“ zu: Antwortet die App nicht rechtzeitig (Standard 10 s, in der Route laufen Airtable und Kitly), kommt derselbe Webhook erneut. `processBookingCreatedForCustomerPortal` erzeugt bei jedem Aufruf einen neuen Zugang und eine neue Mail; der Link aus der ersten Mail wird dabei ungültig. Gegenmittel: in der App anhand von `bookingId` oder des Headers `x-kitly-cal-delivery-id` Doppelte erkennen. Bis dahin `kitly-cal.webhook.timeout-ms` großzügig setzen.

**R3 – Endgültig gescheiterte Zustellungen bemerkt niemand.** Nach sechs Versuchen gilt ein Webhook als tot. Der Kunde ist dann bestätigt, hat aber keinen Kundenbereich-Zugang. Der Dienst loggt das als Fehler und zeigt es unter `/internal/outbox?status=dead`, schlägt aber nicht von sich aus Alarm. Gegenmittel: Alarm auf diese Logzeile oder regelmäßige Abfrage.

**R4 – Öffentliche Slot-Abfrage ohne Bremse.** `/api/cal/slots` der App ist ohne Anmeldung erreichbar und reicht jede Anfrage an den Dienst durch. Bisher fing cal.eu das ab, jetzt landet es auf dem eigenen Server. Die Google-Abfrage ist je Zeitraum 60 Sekunden zwischengespeichert, gleiche Anfragen kosten also kein Google-Kontingent; unterschiedliche Zeiträume schon. Der Dienst hat keine Ratenbegrenzung. Gegenmittel: Ergebnis in der App kurz zwischenspeichern oder am Reverse Proxy begrenzen.

**R5 – Parallelbetrieb mit cal.eu.** Solange über cal.eu noch gebucht werden kann (öffentliche Seite, andere Event-Typen), kennen sich beide Systeme nicht. Derselbe Slot kann in beiden vergeben werden. Gegenmittel: L1 lösen, bevor beide parallel laufen, oder cal.eu beim Umschalten schließen.

**R6 – Personenbezogene Daten liegen jetzt bei dir.** Name und E-Mail-Adresse jedes Interessenten stehen in der eigenen Datenbank, auch von Reservierungen, die nie bezahlt wurden. Bisher war cal.eu dafür Auftragsverarbeiter. Der Dienst löscht nichts. Es fehlen Löschfrist, Löschlauf, Eintrag im Verarbeitungsverzeichnis und ein Blick auf Backups. Ich bin kein Jurist; das sollte jemand prüfen, der es ist.

**R7 – Zwei Repos, ein Vertrag.** App und Dienst liegen jetzt getrennt. Ändert jemand in kitly-cal ein Antwortformat oder in dsb-new den Cal-Client, fällt das in keinem der beiden Repos von allein auf. Der Vertragstest unter `contract/dsb-new/` deckt genau das ab, läuft aber nur, wenn er nach dsb-new kopiert und gegen einen gestarteten kitly-cal gefahren wird. Eine automatische Ausführung (CI) gibt es in diesem Repo noch nicht.

### Mittel

**R8 – Das Mock-Profil gehört nie in den Betrieb.** Mit `SPRING_PROFILES_ACTIVE=mock` liegt alles im Arbeitsspeicher, und `/_mock/*` (Uhr verstellen, alles löschen) ist ohne Schlüssel erreichbar. Die Compose-Dateien setzen das Profil nur im Dienst `mock`. Der Dienst warnt beim Start, wenn er den Arbeitsspeicher nutzt, verweigert den Start aber nicht.

**R9 – Die App behandelt jeden Fehler beim Reservieren als „Slot vergeben“.** `reserveSlot` leitet bei jedem Fehler auf `status=slot-vergeben` um. Ist der Dienst gestört, liest der Kunde „Termin leider nicht mehr verfügbar“ und versucht es mit dem nächsten Slot, ebenfalls erfolglos.

**R10 – Aufrufe der App an den Dienst haben kein Zeitlimit.** `calRequest` und die Slot-Route rufen `fetch` ohne Timeout auf. Hängt der Dienst, hängt die Server Action bis zum Limit von Vercel.

**R11 – Ein Schlüssel für alles.** Wer den API-Schlüssel hat, kann Slots blockieren, Buchungen bestätigen und über `GET /v2/bookings` Namen und E-Mail-Adressen aller Interessenten lesen. Es gibt keine abgestuften Rechte. Mehrere Schlüssel gleichzeitig sind möglich, damit sich rotieren lässt.

**R12 – Slot-Länge steht an zwei Stellen.** Die App nimmt fest zwei Stunden an (`DEFAULT_SLOT_MS` in `lib/cal-slots.ts`, Text „2 Stunden“ auf der Seite). Wird `duration-minutes` im Dienst geändert, zeigt die App falsche Endzeiten. Ein Aufruf mit `format=range` würde das Ende mitliefern; die App fragt ihn heute nicht an.

**R13 – Test-Buchungen liegen in derselben Datenbank.** Sie sind sauber getrennt (eigener Schlüssel, eigene Sperrregel, keine Effekte, getestet in beide Richtungen), aber nicht physisch. Wer direkt in der Datenbank arbeitet, muss die Spalte `sandbox` beachten.

**R14 – Verhalten von cal.eu ist aus Code und Dokumentation abgeleitet, nicht gemessen.** Ich hatte keinen Zugang zu eurem cal.eu-Konto. Antwortformate und Fehlercodes folgen dem, was die App auswertet, und der öffentlichen Cal-Dokumentation. Wo die App sich auf ein Detail verlässt, das ich nicht kenne, würde das erst im Betrieb auffallen.

**R15 – Die Produktions-Compose-Datei ist eine Annahme.** `docker-compose-production.yml` folgt dem Muster von `kitly` (Netzwerk `auto-proxy`, `VIRTUAL_HOST`, `LETSENCRYPT_HOST`). Läuft der Zielserver stattdessen hinter Traefik oder in Coolify, passt dieser Teil nicht und muss durch Labels bzw. die Einstellungen dort ersetzt werden.

**R21 – Refresh-Token von Google.** Der Dienst hängt an einem Refresh-Token deines Google-Kontos. Es verfällt, wenn der OAuth-Zustimmungsbildschirm im Status „Test“ bleibt (nach 7 Tagen), wenn du den Zugriff widerrufst oder das Passwort änderst (je nach Kontoeinstellung). Dann bietet der Dienst bei `closed` keine Slots mehr an, und Termineinträge landen nach sechs Versuchen unter `/internal/outbox?status=dead`. Gegenmittel: Zustimmungsbildschirm auf „In Produktion“ stellen, Alarm auf `external calendar unavailable` und `outbox event is dead`.

**R22 – Neue private Termine wirken mit bis zu 60 Sekunden Verzögerung.** Ein privater Termin, den du gerade eingetragen hast, blockiert Slots erst nach bis zu 60 Sekunden (`kitly-cal.google.cache-seconds`).

### Niedrig

**R16 – Webhook-Signatur ohne Zeitstempel.** Wie bei cal.eu lässt sich ein mitgeschnittener Webhook erneut einspielen. Die Folge wäre eine weitere Kundenbereich-Mail an den echten Kunden (siehe R2), kein Datenabfluss.

**R17 – Uhren zweier Systeme.** Die Haltefrist rechnet die App aus `createdAt` des Dienstes und ihrer eigenen Uhr. Bei 180 Minuten Frist spielen Sekunden Abweichung keine Rolle.

**R18 – Puffer zählen doppelt.** Der Mindestabstand zwischen zwei Buchungen ist `buffer-before + buffer-after`, nicht der größere der beiden Werte. Das hält die Sperrregel in der Datenbank einfach und ist dokumentiert, weicht aber von cal.eu ab, falls dort Puffer gesetzt sind.

**R19 – Ein Kalender, eine Person.** Alle Event-Typen teilen sich denselben Kalender. Teams oder mehrere Berater gibt es nicht.

**R20 – Andere Spring-Boot-Linie als Kitly.** kitly-cal nutzt Spring Boot 4.1.3, Kitly 4.0.1. Grund: Die 4.0-Linie fällt Ende Dezember 2026 aus dem kostenlosen Support, 4.1 läuft bis Juli 2027. Wer beide Dienste pflegt, hat bis zum Update von Kitly zwei Stände.

## Umschalten

Empfohlene Reihenfolge:

1. Echte Regeln aus cal.eu in `config/kitly-cal.yml` übertragen (L8).
2. B1 beheben (dsb-new#91) und Google einrichten (README, „Google Kalender“).
3. Dienst mit Datenbank aufsetzen, dann `scripts/smoke.sh` mit Sandbox-Schlüssel.
4. `POST /internal/webhook/ping` mit dem echten Schlüssel: prüft Adresse, Secret und Weiterleitung. Die Adresse muss die `www`-Domain sein; die Weiterleitung von der Apex-Domain wertet der Dienst als Fehler.
5. Zukünftige cal.eu-Termine übernehmen oder sperren (L4).
6. In Vercel die vier `CALCOM_*`-Variablen umstellen und neu deployen.
7. cal.eu-Buchungsseite schließen (R5). Den Webhook in cal.eu abschalten, sonst kommen dort bestätigte Altbuchungen weiter in der App an.

Fallen dabei:

- **Offene Reservierungen im Moment des Umschaltens.** Wer gerade in cal.eu reserviert hat und nach dem Umschalten zahlt, dessen `uid` kennt kitly-cal nicht. Die App behandelt das als verlorenen Slot und lässt ohne zweite Zahlung neu wählen. Die alte Reservierung bleibt in cal.eu offen, weil das Housekeeping jetzt kitly-cal fragt. Deshalb zu einer ruhigen Zeit umschalten und in cal.eu einmal von Hand aufräumen.
- **Zurückschalten** geht mit denselben vier Variablen. Buchungen, die inzwischen in kitly-cal entstanden sind, kennt cal.eu dann nicht.

## Was geprüft ist und was nicht

Geprüft:

- 97 Java-Tests, davon 17 gegen ein echtes PostgreSQL 16 mit echten parallelen Verbindungen: Von 25 gleichzeitigen Reservierungen desselben Slots gewinnt genau eine; von zwölf gleichzeitigen HTTP-Anfragen ebenso.
- Der Vertragstest mit 12 Fällen: Der bestehende Cal-Client aus dsb-new lief unverändert und über echtes HTTP gegen den gestarteten Spring-Dienst, einschließlich Webhook in die echte Route der App, die die Signatur angenommen hat.
- Das gebaute Jar gegen eine leere PostgreSQL-Datenbank: Flyway legt das Schema an, die Beispiel-Konfiguration lädt, `scripts/smoke.sh` läuft mit Sandbox-Schlüssel durch, der Webhook kam signiert bei einem echten HTTP-Empfänger an. Dasselbe mit eigenem Schema und den JDBC-Optionen für Pooler (`prepareThreshold=0`, `preferQueryMode=simple`).
- Die HMAC-Signatur gegen Werte, die unabhängig mit Python berechnet wurden.
- Neun absichtlich eingebaute Fehler (z. B. Signatur über falschen Text, Sandbox blockiert echte Slots in der Datenbank, Bestätigen nicht wiederholbar, Weiterleitungen folgen) wurden von den Tests jeweils erkannt.
- Die Compose-Dateien mit `docker compose config` auf gültige Syntax.

Nicht geprüft:

- **Der Bau des Docker-Images und `docker compose up`.** In meiner Umgebung lief kein Docker. Die Schritte aus dem Dockerfile (`mvn package`, Start des Jars) habe ich von Hand ausgeführt, allerdings auf Java 21; das Jar ist für Java 17 übersetzt.
- Der Testweg über Testcontainers. Er ist übersetzt, gelaufen sind die Datenbank-Tests über `KITLY_CAL_TEST_DB_URL`.
- Der Betrieb auf einem Server, hinter einem Reverse Proxy und über den Supabase-Pooler selbst.
- Der komplette Funnel im Browser mit Kitly und Stripe. Der Versuch, den Schritt nach der Zahlung nachzustellen, führte zu B1.
- Das tatsächliche Verhalten von cal.eu (R14).

## Entscheidungen, die ich getroffen habe und die du kippen kannst

- **Cal-kompatibles Protokoll statt eigener API.** Grund: Umschalten und Zurückschalten ohne Code-Änderung in der App. Preis: einige Feldnamen von Cal bleiben erhalten.
- **`JdbcClient` mit SQL statt JPA wie in Kitly.** Grund: Sperrregel, Statuswechsel und Outbox sollen im Code so stehen, wie die Datenbank sie ausführt.
- **Aufbau wie Kitly** (`backend/`, Maven, Flyway, Compose-Dateien gleichen Namens), damit ein späteres `frontend/` daneben passt.
- **Spring Boot 4.1.3 statt 4.0.1** (R20).
- **Bei nicht erreichbarem Kalender keine Slots anbieten** (`busy-time-failure-mode: closed`). Die Alternative kostet keinen Umsatz bei Kalenderausfall, riskiert aber Doppelbelegung.
- **Die frühere TypeScript-Fassung in dsb-new ist damit überholt.** Der Patch aus dem ersten Durchgang muss nicht eingespielt werden.
