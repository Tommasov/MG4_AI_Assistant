# Voce sulla testata MG4 — cosa c'è e cosa si può chiamare

Riferimento per chi deve far parlare o ascoltare un'app su questa vettura. Come nel
`headunit-reference.md`, la distinzione fra **verificato in auto** e **letto dal firmware**
è la cosa più importante del documento: qui sotto è segnata voce per voce.

## Da dove viene

Firmware in `D:\MG4 Trophy R71\...\usb_ota_update\unpacked\system\system\app\`, pacchetti
`SaicVoiceTTS_overseas_eh32`, `SaicVoiceService_overseas_eh32`, `SaicVoiceVui_overseas_eh32`.
Metodo: `dexdump -d classes.dex`, poi le costanti `TRANSACTION_*` lette **dentro** la classe
`$Stub` dell'interfaccia che si intende chiamare.

Il motore è **Cerence/Nuance Dragon Drive**: `system/speech/natp/lib/` contiene
`libdragondriveasrservice.so`, `libdragondriveprompterservice.so`, `libNVSLEngineLib.so` e
altre. Il `BuildConfig` del pacchetto TTS riporta però `FLAVOR = "iflytek_english_in"` e punta
a `/vr/speech/Res/xTTSRes`: sulla testata convivono riferimenti a due motori, e quale sia
attivo non è stato stabilito.

## L'errore da non rifare

I numeri di transazione **non si contano** sull'elenco dei metodi. In `ITtsService` il dex li
stampa in ordine alfabetico e i valori veri sono un altro ordine: quattro su cinque sarebbero
sbagliati.

| metodo (ordine alfabetico del dex) | contando | valore vero |
|---|---|---|
| `promptCommonWords` | 1 | **1** |
| `promptCommonWordsByLang` | 2 | **4** |
| `promptCommonWordsByLangWithStatus` | 3 | **5** |
| `promptCommonWordsWithStatus` | 4 | **2** |
| `stopPrompt` | 5 | **3** |

## Sintesi vocale — `com.saicmotor.voicetts`

Servizio `com.saicmotor.voicetts.TtsService`, `exported="true"`, **nessun
`android:permission`**.

```
descriptor: com.saicmotor.voicetts.ITtsService

tx 1  promptCommonWords(String testo, boolean, String sourceId)
tx 2  promptCommonWordsWithStatus(String, boolean, String, IPromptCallBack)
tx 3  stopPrompt()
tx 4  promptCommonWordsByLang(String testo, int lingua, boolean, String sourceId)
tx 5  promptCommonWordsByLangWithStatus(String, int, boolean, String, IPromptCallBack)

descriptor: com.saicmotor.voicetts.IPromptCallBack
tx 1  onSuccess()      tx 2  onError()      tx 3  onSpeakCompleted()
```

Marshalling di `tx 4`, confermato leggendo il Proxy generato da AIDL nel dex:

```
writeInterfaceToken → writeString(testo) → writeInt(lingua)
                    → writeInt(booleano) → writeString(sourceId) → transact(4)
poi reply.readException()
```

`sourceId`: la stringa vuota va bene — le costanti del servizio stesso
(`TTS_GREETING_SOURCE_ID`, `TTS_SECURITY_SOURCE_ID`) sono entrambe `""`.

**Il booleano**: significato ignoto. Niente nel dump lo nomina. Provati `true` e `false`, in
auto entrambi accettati e pronunciati; la differenza non è stata isolata.

### Costanti di lingua (classe `Constant`)

```
ENG_GBR 1   FRE_FRA 2   DEU_DEU 3   SPA_ESP 4   DUT_NLD 5   DAN_DNK 6   NOR_NOR 7
SWE_SWE 8   ITA_ITA 9   POR_PRT 10  GRE_GRC 11  FIN_FIN 12  TUR_TUR 13  POL_POL 14
```

Sono il **catalogo del motore, non l'inventario della vettura**. Le lingue effettivamente
spedite, come stringhe presenti in tutti e tre i dex, sono dieci e non comprendono l'italiano:

```
ENG-UK  ENG-AUS  ENG-IN  FRE-EU  GER-EU  SPN-EU  SPN-SA  ARAB-GCC  HINDI-IN  Thai-Thai
```

### Verificato in auto (18/09/2026, AUTUS SAIC, adapter 3.4.0)

- `bind`: **connesso**. Un'app di terze parti può legarsi al servizio.
- Tutte e sei le combinazioni provate: **accettate**, nessuna eccezione.
- Le frasi sono state **pronunciate ad alta voce**.
- Con `lingua = 9` (italiano) il testo italiano è stato letto **con voce e pronuncia
  inglesi**: il codice è accettato ma la voce italiana non è installata.

**Conclusione operativa**: il TTS di bordo è utilizzabile, gratuito, istantaneo e funziona
offline — **ma solo in inglese**. Per l'italiano serve una sintesi remota.

### Non verificabile dal firmware

`Config.IFLYTEK_TTS_RES_DIS = "/vr/speech/Res/xTTSRes"`. Nel dump `/vr` è vuota: è una
partizione separata e il payload OTA contiene solo `bl2, bl33, boot, dtbo, system, tee,
vbmeta, vendor`. Quali voci siano davvero installate non si legge da lì.

## Riconoscimento vocale — non utilizzabile per trascrivere

`com.saicmotor.voiceservice`, servizio `VrSpeechService` (`exported="true"`, intent-filter
`com.saic.SaicMotorActionService`). Il suo `onBind` restituisce
`com.saicmotor.voicevui.services.SaicMotorActionService$Stub`.

```
descriptor: com.saicmotor.voicevui.services.SaicMotorActionService

tx 1  doAction(Intent, VrResultListener)
tx 2  doSyncAction(Intent) → ResultInform
tx 3  setNotificationListener(NotificationListener)
tx 4  enterVoiceAppNotify()      tx 5  exitVoiceAppNotify()
tx 6  VRStatusNotify             tx 7  offlineCommandNotify()
tx 8  putIntToSystemSettings(String, int)
tx 9  putStringToSystemSettings(String, String)
tx 10 getProjectConfig() → ProjectConfig
```

**La direzione è opposta a quella che serve.** Il VUI riconosce il parlato per conto suo, lo
trasforma in un `Intent` e chiama questo servizio perché lo **esegua**. Nessun metodo
restituisce una trascrizione: `ResultInform` porta `errorCode`, `errorMsg`,
`isOperationSuccess` e un `Bundle` di esito. `NotificationListener` notifica eventi
(`startWakeUpListeningNotify`, `changeLanguage`, …), mai testo.

Per avere testo libero dalla voce serve una trascrizione remota.

### Pista non esplorata

`doAction(Intent, …)` esegue azioni del veicolo. Se ci si può legare, un'app potrebbe far
**fare** cose all'auto invece che solo parlarne. Manca il vocabolario degli `Intent`
accettati: è un altro giro di dexdump, questa volta su `SaicVoiceVui` (offuscato con
ProGuard, quindi più faticoso).

## Tasti al volante — `com.saic.keyevent.hardkey.report`

### Verificato in auto (18/09/2026, AUTUS SAIC, adapter 3.4.0)

Il broadcast **arriva a un'app qualunque** con un receiver registrato a runtime, per ogni
pressione, con `down`/`up` e il flag di pressione lunga distinguibile:

| keycode | tasto | assegnazione di fabbrica |
|---|---|---|
| 287 | voce | assistente OEM (pressione breve, non cedibile) |
| 286 | stella vuota | rigenerazione — **riassegnabile dal sistema** |
| 17 | stella piena | telecamera — **riassegnabile dal sistema** |

Il broadcast **riferisce** la pressione, non la sostituisce: arriva anche mentre il tasto
esegue la sua azione assegnata, quindi ascoltarlo non impedisce alla telecamera di aprirsi.

Una pressione lunga si riconosce così: il `down` si ripete dopo circa un secondo con
`long true`, e l'`up` arriva al rilascio.

```
16:44:05  keycode 287, down true,  long false
16:44:06  keycode 287, down true,  long true     <- lunga
16:44:08  keycode 287, down false, long false
```

### Smentito: i tasti media

Un `MediaSession` attivo che dichiarava di essere in riproduzione, quindi con la pretesa più
forte possibile sui tasti media, **non ha ricevuto nulla** dal volante in tutta la prova. La
via `MEDIA_BUTTON` non esiste su questa vettura. Una funzione dell'app ci era stata costruita
sopra per un mese, sulla deduzione — corretta in sé — che `MEDIA_BUTTON` sia uno dei pochi
broadcast ancora consegnati a un receiver da manifest: ragionamento giusto applicato alla metà
sbagliata del problema.

### Aperto: il receiver da manifest

Se il broadcast raggiunga un receiver **dichiarato nel manifest**, cioè se il volante possa
svegliare l'app spenta, non è ancora stato misurato in auto. Sull'emulatore:

- broadcast implicito (`am broadcast -a …`) → il receiver da manifest **non** scatta, come
  prevedono le restrizioni di Android 8;
- broadcast indirizzato (`-p com.tommasov.mg4assistant`) → **scatta**, ad app uccisa.

Quindi la domanda è una sola e ben posta: il mittente SAIC indirizza il proprio broadcast?
La prova è di trenta secondi — apri la finestra di osservazione, chiudi l'app, premi il
volante, cerca una riga marcata `manifest`.

## Architettura che ne segue

| pezzo | dove | note |
|---|---|---|
| microfono | **locale** | verificato: picchi 2777 / 4797 / 4241 parlando. `FEATURE_MICROPHONE` riporta `false` — **non usarlo come condizione**, mente |
| voce → testo | remoto (`whisper-1`) | nessuna alternativa locale |
| risposta | remoto (`gpt-*`) | |
| testo → voce | `tts-1` se serve italiano; TTS di bordo se basta l'inglese | il TTS locale dimezza il traffico e azzera la latenza |

Traffico per scambio (domanda 5 s, risposta 8 s, +25% di margine): **65 KB** con voce remota,
**26 KB** con voce locale. Su 1 GB/mese sono rispettivamente ~16.000 e ~40.000 scambi.

Due vincoli che discendono dal traffico, non dal gusto:

- **comprimere l'audio in uscita** (AAC 24 kbps: 15 KB contro i 156 del WAV grezzo);
- **push-to-talk con tetto di durata**, mai microfono aperto: in ascolto continuo un
  gigabyte se ne va in 99 ore a 24 kbps, in 9 ore se l'audio è grezzo.
