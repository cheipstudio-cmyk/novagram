# Turbogram

Client Android leggero per Telegram, basato su MTProto via TDLib. Solo Android, solo arm64. Costruito su misura per essere veloce, sobrio e offrire il minimo indispensabile: lista chat, messaggi, invio testo, foto, file e note vocali, gruppi e canali, notifiche locali.

Estetica "Editorial Dark": fondo carboncino profondo, testo crema caldo, accento ambra. Tipografia Instrument Serif italico sui display, DM Sans nel corpo. Niente blu Telegram, niente icone arrotondate da SaaS.

**Status**: 0.1.0, MVP funzionante. Sviluppato da Second Dream.

---

## Cosa fa Turbogram

Chat private, gruppi (basic e supergroup), canali. Invio e ricezione di testo, foto (via system picker, niente permessi storage extra), file generici, note vocali (OGG/Opus 48 kHz mono, formato nativo Telegram, niente transcoding).

Notifiche locali con titolo chat, nome del mittente nei gruppi, raggruppamento sulla lockscreen. Le chat silenziate (mute impostato su Telegram ufficiale) vengono rispettate. Indicatori visivi distinti per gruppi e canali nella lista.

**Cosa NON fa**: chiamate vocali e video. Quelle richiederebbero `tgcalls` (engine media real-time C++ ~50k righe, build NDK custom con WebRTC/OpenSSL/OpenH264). Fuori scope del progetto.

Altre cose escluse per scelta: stickers animati, reazioni complete, live, stories. Per quelle c'è il client ufficiale.

---

## Credenziali API Telegram

Le credenziali (api_id + api_hash) sono incorporate nel build via `BuildConfig`. Il valore di default è dichiarato in `app/build.gradle.kts`. All'avvio l'app le copia in DataStore e procede direttamente al login con numero di telefono, senza schermate di configurazione.

**Tieni il repository privato.** Le credenziali sono visibili nel source. Se mai dovessi rendere il repo pubblico, sposta i due valori come secret GitHub (`TG_API_ID`, `TG_API_HASH`) e rimuovili dal sorgente: il workflow CI le legge da secret e le inietta in `BuildConfig` al build time, identico runtime ma sorgente pulito.

Per ruotare le credenziali senza toccare il codice:

```
Settings > Secrets and variables > Actions > New repository secret
  TG_API_ID    -> nuovo id
  TG_API_HASH  -> nuovo hash
```

I secret hanno priorità sul default hardcoded.

### Ottenere nuove credenziali

1. https://my.telegram.org/auth
2. Login con il tuo numero
3. API development tools > Crea nuova applicazione
4. Annota `App api_id` (intero) e `App api_hash` (32 char hex)

Vanno mai pubblicate in chiaro su un repo pubblico.

---

## Prerequisiti

### Repository GitHub

L'APK viene costruito da GitHub Actions. Project su un repo (**privato**, vedi sopra sulle credenziali).

---

## Build e release

Il pattern di rilascio è uno solo:

```bash
git add -A
git commit -m "v0.1.0"
git push
git tag v0.1.0
git push --tags
```

Il push del tag attiva `release.yml`, che:

1. Costruisce TDLib (branch `master`) da sorgente per `arm64-v8a` (~35 minuti la prima volta, poi cachato finché TDLIB_REF e NDK_VERSION non cambiano)
2. Assembla l'APK release firmato
3. Crea una GitHub Release con l'APK in allegato

### Aggiornamenti

Bumpa `versionCode` e `versionName` in `app/build.gradle.kts`, poi un nuovo tag. Senza tag il workflow non parte.

---

## Firma APK

Di default il workflow genera una keystore effimera per ogni run. Per uso personale e sideload va bene, ma la firma cambia ad ogni release e l'APK non si aggiorna in-place.

Per una firma stabile crea una keystore locale e aggiungi questi quattro secret nel repo GitHub:

```bash
keytool -genkeypair -v \
  -keystore release.jks \
  -alias turbogram \
  -keyalg RSA -keysize 2048 -validity 36500 \
  -dname "CN=Second Dream, O=Second Dream, C=IT"

base64 -w0 release.jks > release.jks.b64
```

| Secret              | Contenuto                       |
| ------------------- | ------------------------------- |
| `KEYSTORE_BASE64`   | contenuto di `release.jks.b64`  |
| `KEYSTORE_PASSWORD` | password dello store            |
| `KEY_ALIAS`         | `turbogram`                     |
| `KEY_PASSWORD`      | password della chiave           |

---

## Architettura

```
app/
  src/main/
    kotlin/com/secondream/turbogram/
      App.kt                    Application: init AppSettings, NotificationHelper, TdClient
      MainActivity.kt           ComponentActivity con Compose
      settings/AppSettings.kt   DataStore per api_id/api_hash
      td/
        TdClient.kt             Singleton wrapper su org.drinkless.tdlib.Client
        AuthState.kt            Stati di autorizzazione (sealed class)
      ui/
        AppRouter.kt            NavHost: config, login, chats, chat
        theme/                  Color, Type (Google Fonts), Theme
        screens/                ApiConfig, Login, ChatList, Chat
        components/MessageBubble.kt
      notifications/
        NotificationHelper.kt   Canali, build delle notifiche con sender name nei gruppi
        TdService.kt            Foreground service per ricevere messaggi
      util/
        FileUtils.kt            Copia URI in cache per TDLib
        VoiceRecorder.kt        MediaRecorder OGG/Opus 48 kHz mono

  libs/
    tdlib.aar                   Generato in CI, non committato

.github/workflows/release.yml   Build TDLib + APK su tag push
```

### Scelte tecniche

**TDLib invece di Bot API**. Bot API è per i bot, non vede i messaggi privati come utente. Per un client serve MTProto.

**Solo arm64-v8a**. Riduce il tempo di build di 4x. Copre il 99% dei dispositivi Android moderni.

**Notifiche solo a processo vivo**. Niente FCM, niente Firebase, niente Google Services. Il foreground service `TdService` ascolta `TdClient.newMessages` e mostra notifiche locali. Quando il sistema killa il processo i messaggi arrivano comunque sul cloud Telegram, ma la notifica locale viene mostrata solo al prossimo avvio. Compromesso voluto per restare leggeri.

**Voice note**. Registrazione in OGG/Opus a 48 kHz mono. Niente transcoding.

**Foto e file**. Foto via `PickVisualMedia` (system picker, nessun permesso storage), file via `OpenDocument`. Le URI vengono copiate in cache prima di passarle a TDLib.

**targetSdk 35**. Conforme ai requisiti Play Store 2025.

---

## Sviluppo locale (opzionale)

Il workflow CI è la fonte di verità. Per compilare in locale ti serve:

1. JDK 17
2. Android SDK con `compileSdk 35` e NDK `26.1.10909125`
3. AAR di TDLib pre-costruito (puoi prenderlo dall'artefatto di una release CI e metterlo in `app/libs/tdlib.aar`)
4. Crea `keystore.properties` nella root con:

```properties
storeFile=../release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Poi:

```bash
./gradlew :app:assembleRelease
```

---

## Roadmap minima

1. Risposte (reply) e citazioni
2. Inoltro messaggi
3. Cancellazione messaggi
4. Ricerca chat
5. Profilo utente
6. Multi-account

---

## Licenza

Codice proprietario, Second Dream. TDLib è sotto Boost Software License 1.0.
