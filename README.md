# GetYourPC

GetYourPC è una web application dedicata alla ricerca e alla pubblicazione di annunci per computer desktop e laptop usati. I visitatori possono cercare dispositivi per tipologia, prezzo e distanza; gli utenti registrati possono pubblicare e gestire i propri annunci, mentre revisori e amministratori dispongono di funzioni di moderazione.

Il progetto rappresenta l'evoluzione di una precedente versione più semplice, basata su interfacce CLI o JavaFX e su modalità di persistenza alternative. La versione operativa usa un'unica applicazione web Spring Boot e Vue, un database PostgreSQL condiviso, autenticazione applicativa, geocodifica server-side e invio di email transazionali.

## Web application

L'istanza pubblica di riferimento è disponibile all'indirizzo:

**[https://getyourpc-operativo.onrender.com](https://getyourpc-operativo.onrender.com)**

L'avvio di un'istanza gratuita può richiedere alcuni secondi dopo un periodo di inattività. Gli endpoint di controllo sono `GET /api/health`, che verifica anche PostgreSQL, e `GET /api/live`, che verifica il processo applicativo.

## Funzionalità

- Ricerca pubblica di desktop e laptop per fascia di prezzo, paese, città e distanza.
- Parola chiave facoltativa che porta in cima i risultati corrispondenti senza nascondere gli altri annunci.
- Registrazione, recupero password, cambio password e cambio email mediante codice di verifica inviato via email.
- Gestione del profilo e cancellazione autonoma dell'account dopo conferma della password.
- Pubblicazione di annunci con specifiche tecniche strutturate e fino a tre fotografie.
- Consultazione e rimozione dei propri annunci.
- Segnalazione di annunci sospetti da parte dei visitatori e degli utenti.
- Moderazione degli annunci segnalati e blocco dei venditori da parte dei ruoli autorizzati.
- Creazione di reviewer da parte dell'amministratore, con password iniziale generata e inviata direttamente al nuovo account.
- Validazione di paese, città e coordinate tramite Geoapify.
- Invio delle email transazionali tramite Mailjet.

GetYourPC facilita la ricerca e il contatto tra venditore e potenziale acquirente. Non gestisce chat, pagamenti o spedizioni. L'email del venditore è visibile nel dettaglio dell'annuncio; il telefono viene mostrato soltanto quando il venditore lo autorizza per quello specifico annuncio.

## Architettura

| Livello | Tecnologie | Responsabilità |
| --- | --- | --- |
| Frontend | Vue 3, HTML e CSS | SPA, navigazione per ruolo, ricerca, profilo e gestione annunci |
| Backend | Java 17, Spring Boot 3.5, Spring MVC | API REST, validazione, autorizzazione e orchestrazione dei flussi |
| Persistenza | PostgreSQL, JDBC, HikariCP | Account, annunci, immagini, codici, sessioni e rate limit condivisi |
| Sessioni | Spring Session JDBC | Sessioni HTTP coerenti tra più istanze del backend |
| Servizi esterni | Geoapify e Mailjet | Geocodifica e invio delle email transazionali |
| Distribuzione | JVM 17 o Docker | Esecuzione locale, su server privati o piattaforme cloud |

Il frontend è incluso nel jar tramite un WebJar Vue e risorse statiche locali. Non esiste un processo Node separato e il browser non dipende da una CDN. Il backend espone la SPA e le API dallo stesso servizio.

L'accesso al database usa un pool HikariCP. Le sessioni e i limiti di richiesta sono salvati su PostgreSQL, quindi non dipendono dalla memoria della singola JVM. Un fingerprint dell'hash della password associato alla sessione rende non valide le sessioni precedenti dopo un cambio o un recupero password.

I package principali seguono una struttura MVC e sono separati per responsabilità:

| Package | Responsabilità |
| --- | --- |
| `it.getyourpc.controller` | Controller REST, health check e gestione centralizzata degli errori |
| `it.getyourpc.model.auth` | Registrazione, login, profilo, verifiche, ruoli e persistenza account |
| `it.getyourpc.model.listing` | Ricerca, pubblicazione, fotografie e persistenza degli annunci |
| `it.getyourpc.model.review` | Moderazione, rimozione annunci e blocco utenti |
| `it.getyourpc.model.geocoding` | Modello geografico e integrazione server-side con Geoapify |
| `it.getyourpc.model.common` | Errori, rate limit e manutenzione del database |
| `it.getyourpc.mail` | Integrazione server-side con Mailjet |
| `it.getyourpc.config` | Database, cookie e header di sicurezza |

## Ruoli e flussi operativi

### Visitatore

Senza autenticazione è possibile:

- cercare annunci attivi;
- filtrare per desktop o laptop, prezzo e posizione;
- aprire il dettaglio di un annuncio e consultare i recapiti resi disponibili dal venditore;
- avviare la registrazione o il recupero della password.

### Utente

Un utente autenticato può:

- aggiornare nome, cognome e telefono facoltativo;
- cambiare email o password mediante verifica;
- eliminare il proprio account confermando la password;
- pubblicare annunci e allegare fino a tre immagini JPEG o PNG;
- consultare e rimuovere esclusivamente i propri annunci.

Per i desktop sono richiesti CPU, scheda madre, GPU, RAM, memoria, alimentatore, dissipatore e case. Per i laptop sono richiesti marca, modello, dimensione dello schermo, CPU, GPU, RAM e memoria. Il prezzo massimo accettato per ricerca e pubblicazione è 100.000 euro.

### Reviewer

Il reviewer può:

- consultare gli annunci segnalati dalla community;
- rimuovere un annuncio non conforme;
- bloccare il venditore quando necessario.

La rimozione disattiva l'annuncio senza renderlo più visibile o ricercabile. Il blocco disattiva l'account e tutti i suoi annunci; l'eventuale sessione aperta viene rifiutata alla richiesta successiva.

### Amministratore

L'amministratore può usare le funzioni degli utenti e dei reviewer e può creare nuovi reviewer. La password iniziale viene generata dal backend e inviata direttamente via Mailjet all'indirizzo del nuovo reviewer. L'email invita a cambiarla dal profilo, ma il cambio al primo accesso non è imposto dal backend. Se la consegna fallisce, l'account appena creato viene rimosso come compensazione.

I controlli di ruolo sono applicati dal backend, indipendentemente dalla visibilità dei pulsanti nel frontend.

## Verifiche account e concorrenza

I codici di verifica sono numerici, hanno cinque cifre, scadono dopo dieci minuti e ammettono al massimo cinque tentativi. Nel database viene salvato soltanto l'hash BCrypt del codice. Una nuova richiesta sostituisce atomicamente il codice precedente e la conferma riuscita lo elimina nella stessa transazione che crea o aggiorna l'account.

Le operazioni composte su account, email, annunci e revisione usano transazioni PostgreSQL. Gli indici univoci e gli UPSERT impediscono che richieste concorrenti creino più codici attivi per lo stesso scopo e account. La creazione di un account e il cambio email serializzano le operazioni concorrenti sullo stesso indirizzo.

Le chiamate Mailjet avvengono dopo il commit del codice, senza mantenere aperta una transazione durante la richiesta HTTP. Il recupero password esegue lo stesso lavoro crittografico anche per email sconosciute e applica una durata minima configurabile, riducendo l'enumerazione degli account tramite tempi di risposta.

## Configurazione

### Requisiti

- Java Development Kit 17 o successivo.
- Maven 3.9 o il wrapper Maven incluso.
- PostgreSQL compatibile con le istruzioni presenti in `db.sql`.
- Credenziali Geoapify per la geocodifica.
- Credenziali Mailjet e un mittente verificato per i flussi email.
- Docker, facoltativo, per la build container e i test di integrazione Testcontainers.

### Variabili d'ambiente

Il file `.env.example` contiene valori segnaposto. Spring Boot non carica automaticamente quel file: in locale deve essere importato nella shell, mentre in produzione le stesse variabili devono essere configurate nel sistema operativo, nel secret manager o nel pannello del provider scelto.

| Variabile | Obbligatoria | Default | Uso |
| --- | --- | --- | --- |
| `JDBC_DATABASE_URL` oppure `DATABASE_URL` | Sì | Nessuno | URL JDBC o URI PostgreSQL; sono accettati anche `postgresql://` e `postgres://` |
| `DB_USERNAME` | Se non incluso nell'URL | Nessuno | Utente PostgreSQL; se presente ha precedenza sulle credenziali incorporate |
| `DB_PASSWORD` | Se non inclusa nell'URL | Nessuno | Password PostgreSQL; se presente ha precedenza sulle credenziali incorporate |
| `DB_POOL_SIZE` | No | `5` | Dimensione del pool JDBC, ammessa tra `1` e `20` |
| `PORT` | No | `8080` | Porta HTTP di Spring Boot |
| `SESSION_COOKIE_SECURE` | No | `false` | Impostare `true` quando il servizio è esposto esclusivamente tramite HTTPS |
| `GEOAPIFY_API_KEY` | Sì per geocodifica e annunci | Nessuno | Chiave Geoapify usata solo dal backend |
| `GEOAPIFY_BASE_URL` | No | `https://api.geoapify.com` | Endpoint Geoapify, modificabile soprattutto nei test |
| `MAILJET_API_KEY` | Sì per i flussi email | Nessuno | Chiave pubblica usata come username HTTP Basic |
| `MAILJET_SECRET_KEY` | Sì per i flussi email | Nessuno | Chiave segreta usata come password HTTP Basic |
| `MAILJET_SENDER_EMAIL` | Sì per i flussi email | Nessuno | Indirizzo mittente verificato nello stesso account Mailjet |
| `MAILJET_SENDER_NAME` | No | `GetYourPC` | Nome visualizzato come mittente |
| `MAILJET_BASE_URL` | No | `https://api.mailjet.com` | Endpoint Mailjet, modificabile soprattutto nei test |
| `MAINTENANCE_CLEANUP_CRON` | No | Ogni 15 minuti | Espressione cron per codici scaduti e vecchi rate limit |
| `FORGOT_PASSWORD_MINIMUM_RESPONSE_MS` | No | `1000` | Durata minima del recupero password, ammessa tra `0` e `5000` ms |
| `BOOTSTRAP_ACCOUNT_ENABLED` | No | `false` | Abilita temporaneamente la creazione del primo account |
| `BOOTSTRAP_ACCOUNT_EMAIL` | Solo per bootstrap | Nessuno | Email del primo account |
| `BOOTSTRAP_ACCOUNT_PASSWORD` | Solo per bootstrap | Nessuno | Password iniziale, da 8 caratteri a 72 byte UTF-8 e diversa dall'email |
| `BOOTSTRAP_ACCOUNT_NAME` | Solo per bootstrap | Nessuno | Nome del primo account |
| `BOOTSTRAP_ACCOUNT_SURNAME` | Solo per bootstrap | Nessuno | Cognome del primo account |

La URL di produzione deve richiedere TLS, ad esempio:

```text
jdbc:postgresql://HOST:PORT/NOME_DATABASE?sslmode=require
```

### Inizializzazione del database

`db.sql` è lo schema completo del progetto. Crea o aggiorna tabelle, indici e strutture per account, annunci, fotografie, codici di verifica, sessioni e rate limit. Non contiene `DROP TABLE`, dati dimostrativi o password ed è progettato per essere rieseguito dopo gli aggiornamenti.

Eseguire lo script con un client PostgreSQL, ad esempio:

```bash
psql 'postgresql://UTENTE:PASSWORD@HOST:PORT/NOME_DATABASE?sslmode=require' -f db.sql
```

Il database può essere gestito da Aiven, Render, AWS, Azure, Google Cloud o qualsiasi altro servizio PostgreSQL compatibile. Il provider non cambia il contratto applicativo.

### Bootstrap del primo account

Il database non contiene account predefiniti. Per il primo avvio configurare temporaneamente:

```bash
export BOOTSTRAP_ACCOUNT_ENABLED='true'
export BOOTSTRAP_ACCOUNT_EMAIL='amministratore@example.com'
export BOOTSTRAP_ACCOUNT_PASSWORD='password-iniziale-sicura'
export BOOTSTRAP_ACCOUNT_NAME='Nome'
export BOOTSTRAP_ACCOUNT_SURNAME='Cognome'
```

Il bootstrap crea sempre un normale account con ruolo `user` e non sovrascrive un account già presente con la stessa email. Dopo il primo avvio impostare `BOOTSTRAP_ACCOUNT_ENABLED=false`, rimuovere `BOOTSTRAP_ACCOUNT_PASSWORD` dall'ambiente e ridistribuire il servizio.

Per promuovere il primo account ad amministratore, eseguire una sola volta:

```sql
UPDATE Users
SET role = 'admin'
WHERE LOWER(email) = LOWER('amministratore@example.com') AND status = 'active';
```

Da quel momento l'amministratore può creare i reviewer dall'interfaccia.

## Avvio locale

Clonare il repository principale:

```bash
git clone https://github.com/Alessio-Colantoni/GetYourPC.git
cd GetYourPC
```

La stessa release è pubblicata anche nel mirror
[`GetYourPC_Operativo`](https://github.com/Alessio-Colantoni/GetYourPC_Operativo).

Inizializzare PostgreSQL con `db.sql`, quindi preparare l'ambiente:

```bash
cp .env.example .env
# Sostituire tutti i valori segnaposto prima di proseguire.
set -a
source .env
set +a
```

Avviare l'applicazione:

```bash
./mvnw spring-boot:run
```

La web application sarà disponibile su `http://localhost:8080`. Verificare lo stato con:

```bash
curl --fail http://localhost:8080/api/health
curl --fail http://localhost:8080/api/live
```

## Installazione e distribuzione

Il progetto non dipende dalle API di uno specifico provider. Richiede una JVM Java 17 o un runtime Docker, PostgreSQL raggiungibile, le variabili documentate e terminazione HTTPS fornita dall'host o da un reverse proxy.

### Distribuzione come jar

1. Eseguire `./mvnw verify`.
2. Copiare `target/getyourpc-2.0.0.jar` sul server.
3. Installare una runtime Java 17.
4. Configurare le variabili d'ambiente tramite il sistema operativo o un secret manager.
5. Avviare `java -jar getyourpc-2.0.0.jar` con il process manager scelto.
6. Esporre la porta configurata tramite HTTPS.
7. Usare `/api/health` come health check e `/api/live` come liveness check.

### Distribuzione con Docker

Eseguire prima i test, perché il `Dockerfile` genera il jar senza avviare la suite:

```bash
./mvnw verify
docker build -t getyourpc .
docker run --rm --env-file .env -p 8080:8080 getyourpc
```

L'immagine finale usa Java 17, esegue il processo con un utente non root ed espone la porta `8080`.

### Distribuzione su una piattaforma cloud

Su qualsiasi piattaforma compatibile con Docker o applicazioni Java:

1. collegare il repository oppure pubblicare l'immagine in un registry;
2. scegliere il `Dockerfile` o il jar come artefatto;
3. configurare database, Geoapify e Mailjet mediante variabili o secret;
4. impostare `SESSION_COOKIE_SECURE=true` quando il dominio usa HTTPS;
5. inizializzare PostgreSQL con `db.sql` prima di aprire il servizio agli utenti;
6. configurare `/api/health` come health check;
7. eseguire l'eventuale bootstrap e rimuovere subito la password dall'ambiente;
8. verificare registrazione, invio email, geocodifica e pubblicazione di un annuncio.

Questi passaggi si applicano a Render, Railway, Fly.io, Google Cloud Run, AWS, Azure e altri host compatibili. Aiven è una possibile sorgente PostgreSQL, non un requisito.

### Esempio Render

Il file `render.yaml` è una configurazione opzionale per un Web Service Docker. Per usarlo:

1. creare un Blueprint dal repository o un Web Service basato sul `Dockerfile`;
2. configurare le variabili obbligatorie del database, Geoapify e Mailjet;
3. lasciare `DB_POOL_SIZE=5` e impostare `SESSION_COOKIE_SECURE=true`, salvo esigenze diverse;
4. configurare temporaneamente le variabili bootstrap se il database è nuovo;
5. verificare `/api/health` e i flussi principali;
6. disabilitare il bootstrap e rimuoverne la password.

Render ospita l'istanza pubblica di riferimento, ma il codice non usa API proprietarie Render.

## API principali

```text
POST   /api/auth/login
GET    /api/auth/me
POST   /api/auth/logout
POST   /api/auth/register/start
POST   /api/auth/register/confirm
POST   /api/auth/password/forgot/start
POST   /api/auth/password/forgot/confirm
POST   /api/auth/password/change/start
POST   /api/auth/password/change/confirm
POST   /api/auth/email/change/start
POST   /api/auth/email/change/confirm
PATCH  /api/auth/profile
DELETE /api/auth/account
POST   /api/admin/reviewers
GET    /api/geocoding
GET    /api/listings
POST   /api/listings
GET    /api/listings/mine
PATCH  /api/listings/{id}
DELETE /api/listings/{id}
POST   /api/listings/{id}/report
GET    /api/listings/{id}/photos/{numero}
GET    /api/reviewer/listings
DELETE /api/reviewer/listings/{id}
GET    /api/health
GET    /api/live
```

La creazione di un annuncio usa `multipart/form-data`: una parte JSON `listing` e fino a tre parti `photos`. Sono accettate esclusivamente immagini JPEG o PNG valide, fino a 5 MB ciascuna, 12 megapixel e 6.000 pixel per lato.

## Test e verifiche

Eseguire la suite completa con:

```bash
./mvnw verify
```

I test coprono autenticazione, bootstrap, profilo, verifiche, rate limit, ruoli, immagini, annunci, moderazione, integrazioni esterne, configurazione del database, header di sicurezza e risorse frontend. I test PostgreSQL basati su Testcontainers vengono eseguiti quando Docker è disponibile.

Per avviare soltanto i test:

```bash
./mvnw test
```

## Portabilità e limiti operativi

- Il codice non usa API proprietarie di Render o Aiven.
- Sessioni e rate limit sono condivisi su PostgreSQL e restano coerenti con più istanze del backend.
- Il progetto è indipendente dal provider di hosting, ma usa funzionalità specifiche di PostgreSQL e non è portabile su un altro motore SQL senza adattamenti.
- La disponibilità di registrazione, recupero credenziali e provisioning reviewer dipende da Mailjet.
- La ricerca geografica e la pubblicazione dipendono da Geoapify.
- Il progetto non include un sistema di pagamenti, chat o spedizioni.

## Struttura del repository

| Percorso | Contenuto |
| --- | --- |
| `src/main/java/it/getyourpc/controller/` | Controller MVC e gestione delle richieste HTTP |
| `src/main/java/it/getyourpc/model/` | Modello MVC, servizi applicativi e accesso ai dati |
| `src/main/resources/static/` | SPA Vue, fogli di stile e immagini |
| `src/main/resources/application.properties` | Porta, sessioni, bootstrap e servizi esterni |
| `src/test/` | Test unitari, web e di integrazione PostgreSQL |
| `db.sql` | Schema PostgreSQL idempotente |
| `.env.example` | Esempio di configurazione senza segreti reali |
| `pom.xml` | Dipendenze e configurazione Maven |
| `mvnw`, `mvnw.cmd` | Wrapper Maven per sistemi POSIX e Windows |
| `Dockerfile` | Build multi-stage e runtime Java non root |
| `render.yaml` | Esempio opzionale di deploy Render |
