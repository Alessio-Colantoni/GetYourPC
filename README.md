# GetYourPC

GetYourPC è una web application per cercare e pubblicare annunci di computer desktop e laptop usati. I visitatori possono filtrare gli annunci per prezzo e distanza; gli utenti registrati possono vendere i propri dispositivi, mentre reviewer e amministratori gestiscono la moderazione.

Il progetto riunisce frontend Vue e API REST in un'unica applicazione Spring Boot, con PostgreSQL per dati e sessioni, Geoapify per la geocodifica e Mailjet per le email transazionali.

## Web application

L'istanza pubblica è disponibile su:

**[https://getyourpc-operativo.onrender.com](https://getyourpc-operativo.onrender.com)**

`GET /api/health` verifica applicazione e database; `GET /api/live` verifica il processo applicativo.

## Funzionalità

- Ricerca pubblica di desktop e laptop per tipologia, prezzo, posizione, distanza e parola chiave.
- Registrazione e recupero delle credenziali tramite codice inviato via email.
- Aggiornamento di profilo, email e password.
- Pubblicazione e gestione di annunci con specifiche tecniche e fino a tre fotografie.
- Visualizzazione dei recapiti scelti dal venditore.
- Segnalazione di annunci da parte di visitatori e utenti.
- Moderazione degli annunci segnalati e blocco dei venditori.
- Creazione di reviewer da parte dell'amministratore.
- Geocodifica server-side tramite Geoapify.
- Invio delle email tramite Mailjet.

GetYourPC non gestisce chat, pagamenti o spedizioni.

## Architettura

| Livello | Tecnologie | Responsabilità |
| --- | --- | --- |
| Frontend | Vue 3, HTML, CSS | SPA, ricerca, profilo e gestione annunci |
| Backend | Java 17, Spring Boot 3.5, Spring MVC | API REST, validazione e autorizzazione |
| Database | PostgreSQL, JDBC, HikariCP | Account, annunci, immagini e dati applicativi |
| Sessioni | Spring Session JDBC | Sessioni HTTP condivise tra le istanze |
| Servizi esterni | Geoapify, Mailjet | Geocodifica e invio email |
| Esecuzione | Jar Java o Docker | Avvio su server e piattaforme cloud |

Il frontend è incluso nel jar tramite WebJar e risorse statiche: non richiede un processo Node né una CDN. Sessioni e limiti di richiesta sono salvati in PostgreSQL.

### Sicurezza e credenziali

Le password devono avere almeno otto caratteri, non possono coincidere con l'email e sono limitate a 72 byte UTF-8, valore massimo elaborato da BCrypt. Nel database viene salvato un hash BCrypt con costo `12`. I codici di verifica sono numerici di cinque cifre, vengono salvati soltanto come hash, scadono dopo dieci minuti e consentono al massimo cinque tentativi.

L'autenticazione usa cookie di sessione `HttpOnly` con `SameSite=Lax`; su HTTPS va impostato `SESSION_COOKIE_SECURE=true`. Le sessioni risiedono in PostgreSQL e contengono un fingerprint derivato dall'hash della password: cambio e recupero password rendono quindi non valide le sessioni precedenti. Il browser comunica con la stessa origine e non conserva token applicativi.

Il backend imposta Content Security Policy, HSTS sulle richieste HTTPS, protezione da embedding e altri header restrittivi. Le immagini caricate vengono decodificate, validate e ricodificate prima del salvataggio; i limiti di richiesta sono condivisi nel database. Le connessioni PostgreSQL remote devono usare TLS e le chiavi di database, Geoapify e Mailjet restano nell'ambiente del server. Le variabili di bootstrap devono essere temporanee e la relativa password va rimossa dopo la creazione del primo account.

## Ruoli e flussi operativi

### Visitatore

Senza autenticazione è possibile:

- cercare e aprire gli annunci attivi;
- filtrare desktop e laptop per prezzo e posizione;
- consultare i recapiti resi disponibili dal venditore;
- segnalare un annuncio;
- avviare registrazione o recupero password.

### Utente

Un utente autenticato può:

- aggiornare nome, cognome e telefono;
- cambiare email o password tramite verifica;
- eliminare il proprio account;
- creare, modificare e rimuovere i propri annunci;
- allegare fino a tre immagini JPEG o PNG.

Il prezzo massimo accettato è 100.000 euro. Il telefono viene mostrato solo se il venditore lo abilita per lo specifico annuncio.

### Reviewer

Il reviewer può consultare gli annunci segnalati, rimuoverli e, quando necessario, bloccare il venditore. Il blocco disattiva l'account e tutti i suoi annunci.

### Amministratore

L'amministratore può usare le funzioni degli utenti e dei reviewer e può creare nuovi reviewer. Il backend genera la password iniziale e la invia al nuovo account tramite Mailjet; se l'invio fallisce, l'account appena creato viene eliminato.

## Configurazione

### Requisiti

- JDK 17 o successivo.
- Il wrapper Maven incluso nel repository.
- PostgreSQL compatibile con `db.sql`.
- Una chiave Geoapify.
- Un account Mailjet con mittente verificato.
- Docker, facoltativo, per eseguire l'applicazione in container.

### Variabili d'ambiente

Il file `.env.example` contiene solo segnaposto, non deve essere compilato con valori reali e Spring Boot non lo carica automaticamente.

| Variabile | Obbligatoria | Default | Uso |
| --- | --- | --- | --- |
| `JDBC_DATABASE_URL` o `DATABASE_URL` | Sì | Nessuno | URL JDBC o URI PostgreSQL |
| `DB_USERNAME` | Se non incluso nell'URL | Nessuno | Utente PostgreSQL |
| `DB_PASSWORD` | Se non inclusa nell'URL | Nessuno | Password PostgreSQL |
| `DB_POOL_SIZE` | No | `5` | Dimensione del pool, da `1` a `20` |
| `PORT` | No | `8080` | Porta HTTP dell'applicazione |
| `SESSION_COOKIE_SECURE` | No | `false` | Impostare `true` quando il servizio usa HTTPS |
| `GEOAPIFY_API_KEY` | Sì | Nessuno | Chiave usata dal backend per la geocodifica |
| `MAILJET_API_KEY` | Sì | Nessuno | Chiave pubblica Mailjet |
| `MAILJET_SECRET_KEY` | Sì | Nessuno | Chiave segreta Mailjet |
| `MAILJET_SENDER_EMAIL` | Sì | Nessuno | Mittente verificato su Mailjet |
| `MAILJET_SENDER_NAME` | No | `GetYourPC` | Nome visualizzato del mittente |
| `BOOTSTRAP_ACCOUNT_ENABLED` | No | `false` | Abilita la creazione del primo account |
| `BOOTSTRAP_ACCOUNT_EMAIL` | Con bootstrap attivo | Nessuno | Email del primo account |
| `BOOTSTRAP_ACCOUNT_PASSWORD` | Con bootstrap attivo | Nessuno | Password da 8 caratteri a 72 byte UTF-8 |
| `BOOTSTRAP_ACCOUNT_NAME` | Con bootstrap attivo | Nessuno | Nome del primo account |
| `BOOTSTRAP_ACCOUNT_SURNAME` | Con bootstrap attivo | Nessuno | Cognome del primo account |

Le connessioni PostgreSQL remote devono usare `sslmode=require`, `verify-ca` o `verify-full`.

### Database e primo amministratore

`db.sql` crea tabelle, indici e strutture necessarie per account, annunci, immagini, sessioni, codici di verifica e limiti di richiesta. Eseguirlo sul database scelto:

```bash
psql 'postgresql://UTENTE:PASSWORD@HOST:PORT/NOME_DATABASE?sslmode=require' -f db.sql
```

Per creare il primo account, impostare temporaneamente:

```bash
export BOOTSTRAP_ACCOUNT_ENABLED='true'
export BOOTSTRAP_ACCOUNT_EMAIL='amministratore@example.com'
export BOOTSTRAP_ACCOUNT_PASSWORD='password-iniziale-sicura'
export BOOTSTRAP_ACCOUNT_NAME='Nome'
export BOOTSTRAP_ACCOUNT_SURNAME='Cognome'
```

Il bootstrap crea un normale utente. Promuoverlo ad amministratore con:

```sql
UPDATE Users
SET role = 'admin'
WHERE LOWER(email) = LOWER('amministratore@example.com')
  AND status = 'active';
```

Dopo la creazione, disabilitare il bootstrap e rimuovere la relativa password dall'ambiente.

## Avvio locale

Clonare il repository:

```bash
git clone https://github.com/Alessio-Colantoni/GetYourPC.git
cd GetYourPC
```

Inizializzare PostgreSQL con `db.sql`, quindi preparare le variabili:

```bash
cp .env.example .env
# Sostituire i valori segnaposto.
set -a
source .env
set +a
```

Avviare l'applicazione:

```bash
./mvnw spring-boot:run
```

La web application sarà disponibile su `http://localhost:8080`.

## Installazione su server

Il repository include un `Dockerfile` multi-stage. Il servizio richiede PostgreSQL, Geoapify, Mailjet e terminazione HTTPS.

### Esempio Render

1. Creare il database PostgreSQL ed eseguire `db.sql`.
2. Creare un Blueprint dal repository oppure un Web Service basato sul `Dockerfile`.
3. Configurare le variabili di PostgreSQL, Geoapify e Mailjet.
4. Impostare `SESSION_COOKIE_SECURE=true`.
5. Per il primo avvio, configurare le variabili `BOOTSTRAP_ACCOUNT_*`.
6. Usare `/api/health` come Health Check Path.
7. Dopo la creazione dell'account, disabilitare il bootstrap e rimuoverne la password.

Il file `render.yaml` configura il servizio Docker e le variabili principali, ma non crea il database PostgreSQL né esegue `db.sql`.

## Struttura del repository

| Percorso | Contenuto |
| --- | --- |
| `src/main/java/it/getyourpc/controller/` | Controller REST e gestione HTTP |
| `src/main/java/it/getyourpc/model/` | Servizi applicativi e accesso ai dati |
| `src/main/java/it/getyourpc/config/` | Database e header di sicurezza |
| `src/main/resources/static/` | Frontend Vue, CSS e immagini |
| `src/main/resources/application.properties` | Configurazione Spring Boot |
| `db.sql` | Schema PostgreSQL |
| `.env.example` | Modello delle variabili d'ambiente |
| `pom.xml` | Dipendenze e configurazione Maven |
| `mvnw`, `mvnw.cmd` | Wrapper Maven |
| `Dockerfile` | Build e runtime Java |
| `render.yaml` | Configurazione opzionale per Render |
