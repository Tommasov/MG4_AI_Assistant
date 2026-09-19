# MG4 AI Assistant

MG4 AI Assistant is a **push-to-talk voice assistant** for the MG4 head unit
(pre-facelift, AAOS 9 / API 28, 1778×720, landscape). You ask a question out loud and
it answers out loud, on your own account with your own API key.

It exists because the car cannot do this by itself, and that is not a guess — it was
measured on the vehicle. There is **no speech-to-text on board**: the factory voice
assistant recognises the commands it was built for and nothing else, and it offers no
recogniser an ordinary app can call. The factory **synthesiser** can be driven by a
third-party app, but it has no Italian voice. So an assistant on this car means
recording the audio here and sending it somewhere that can transcribe it.

The interface and palette follow
[MG4 Simple Launcher](https://github.com/Tommasov/MG4_Simple_Launcher), so the two
look like they belong to the same car.

## How it works

One press, four steps, no typing:

1. **Record.** AAC at 24 kbps, mono, 16 kHz. It starts when you press and stops when
   you stop speaking — about a second of silence ends the take. A take nobody spoke
   into gives up after five seconds; thirty seconds is the hard ceiling.
2. **Transcribe.** The recording goes to OpenAI `whisper-1` and is deleted from the
   car immediately afterwards.
3. **Answer.** The text goes to a chat model — OpenAI or xAI, your choice — with the
   last few turns of the conversation for context.
4. **Speak.** Either OpenAI `tts-1` in one of six voices, or the car's own
   synthesiser. The car's voice costs nothing and speaks only English; the remote
   voices cost money and pronounce Italian properly. Which is better is not something
   to settle on paper, so it is a switch.

The microphone is released and the audio focus given back as soon as it is done, so
music ducks for the question and comes straight back.

## You bring your own key

This app has no account, no server and no subscription. It talks to OpenAI or xAI
**as you**, with a key you provide, and everything it does is billed to you directly
by them. A release build **cannot** carry a key: the constant is empty by
construction, whatever is in the developer's `apikeys.properties`.

Typing a 164-character key on a touchscreen in a car is not a realistic thing to ask,
so there are three ways in and only one of them involves typing:

- **From a file.** Put the key in a text file — any name will do — and get it onto
  the car on a USB stick or in the Download folder. The app opens small text files in
  those places and looks for something beginning `sk-` or `xai-`, shows you which file
  it found it in, and asks before using it.
- **Paste**, if the key is already in the clipboard.
- **Type it**, in a dialog, if you have the patience.

Whichever you use, the key is checked against the service straight away, so a wrong
character is a message on a parked car rather than a mystery on a motorway three days
later. Keys are shown masked afterwards — `sk-proj-…a1B2` — and a coloured dot beside
each service says whether it answered the last time it was asked.

### What it costs

Worked out from the providers' list prices and one exchange's actual quantities:
**about half a eurocent per question**, of which roughly three quarters is the spoken
reply — speech is billed per character and costs more than the thinking does. A hundred
questions come to around sixty cents. Prices change, so treat the ratio as the durable
part and check the figure yourself; use a key with a spending limit either way.

The app keeps its own count — questions, minutes listened, tokens, characters spoken —
because the providers' usage endpoints refuse a project key. Quantities, not money:
prices go stale, and a figure that is wrong is worse than no figure.

## What it does not do

- **No wake word.** Nothing listens until you press. An open microphone in a car is
  both a privacy problem and a data one.
- **No control over the vehicle.** It cannot set the climate, open a window or change
  anything about the car. It answers questions.
- **Nothing offline.** No key or no connection means no assistant.

## Settings

- **Keys** for OpenAI and xAI. The two services are not interchangeable: xAI answers
  questions and has neither transcription nor speech, so hearing and speaking always
  go to OpenAI whatever answers.
- **Model**, listed from your own account rather than hard-coded — which models an
  account can reach changes without warning, and a name typed from memory produces a
  404 that reads, from the driver's seat, exactly like a broken connection.
- **Voice**: the car's own, or one of six remote ones, each sampled aloud the moment
  you pick it.
- **Listens as soon as it opens**, on by default. A gesture that opens the app is only
  half a command if you then have to touch the screen.
- **Steering wheel**, off by default — see below.
- **Diagnostics**: what this head unit can and cannot do, and a report that can be
  sent to the author, because there is nowhere else on this car for it to go.

## The steering wheel

Partly answered, and the part that is answered is worth writing down.

The car broadcasts every wheel key press as `com.saic.keyevent.hardkey.report`, and an
ordinary app **can** hear it while it is running: keycode 287 is the voice key, 286 and
17 are the two configurable stars. A long press is distinguishable from a short one.
Media buttons, which is where this app originally looked, receive nothing at all.

What that leaves is one gesture: a **long press of the voice key**. Its short press
belongs to the factory assistant and cannot be taken from it, and the two stars are
already spent on regeneration and the camera by anyone who has had the car a while.

There is one condition, and it is why the switch ships off: **with Android Auto
connected the long press belongs to Google Assistant**, and this app cannot take it —
the broadcast is a report that arrives after the system has already dispatched the
key, so both would open at once. Leave the switch off if you use Android Auto.

## What leaves the car

Worth being plain about, since this app sends audio somewhere.

- **The recording of your question**, to OpenAI, for transcription. It is deleted from
  the car as soon as the text comes back and is never stored.
- **The text of the conversation**, to whichever service answers, including the last
  few turns for context.
- **The text of the answer**, to OpenAI, if you use a remote voice.
- **Nothing else.** No telemetry, no analytics, no vehicle data. The diagnostics report
  is sent only when you press the button, and it asks first.

## Build

Standard Android project (Java, AGP 8.6, Gradle 8.7, `minSdk 28` / `targetSdk 34`).

```
./gradlew assembleDebug
```

**JDK**: Gradle 8.7 runs on JDK 17–21 and fails on newer ones with a bare
`IllegalArgumentException: <version>` from the Kotlin DSL compiler. Set *Settings →
Build Tools → Gradle → Gradle JDK* to a JDK 17 or 21.

**Keys in a build**: a debug build may carry a key from a git-ignored
`apikeys.properties` at the project root, for the author's own testing. A **release
build never does** — `API_KEY` is declared per build type and is empty in release
whatever the properties file says. That is checked by grepping the dex, not by
remembering.

**Release signing**: credentials come from a git-ignored `keystore.properties`.
Without it the project still builds; the release type is left unsigned.

## Status

Early. The chain works end to end in the car, the settings screen is complete, and the
wheel is understood as far as the paragraph above says. Still open: whether the car's
broadcast reaches a receiver declared in the manifest, which decides whether the wheel
can wake the app when it is closed.

Vehicle findings — what was verified on the car rather than read out of the firmware —
are kept in [`voice-reference.md`](voice-reference.md).

## Disclaimer (English)

This project is provided **for study and educational purposes only**. It is an
experimental, non-commercial project and is not affiliated with, endorsed by, or
supported by SAIC, MG, or any vehicle manufacturer.

The software is provided "as is", without warranty of any kind, express or implied.
The author accepts **no liability** for any direct, indirect, incidental, or
consequential damage of any kind — including but not limited to damage to the vehicle,
its infotainment system, software, or data, loss of functionality, or safety-related
consequences — arising from the installation or use of this app. You use it entirely
**at your own risk**. Do not interact with the app while driving.

### Your key, your bill

The app uses the API key you give it and every request is charged to your account by
OpenAI or xAI. The author has no visibility of your usage and no part in your billing.
Use a key with a spending limit, and remember that a key in a text file on a USB stick
is a password sitting in a text file on a USB stick — delete it once it is imported.

### What an assistant is not

Answers come from a language model and can be confidently wrong. Do not rely on this
app for anything that matters — and never for anything about the safe operation of the
vehicle.

## Avvertenze (Italiano)

Progetto **a scopo di studio**, sperimentale e non commerciale, non affiliato né
supportato da SAIC, MG o altri costruttori. Il software è fornito "così com'è", senza
garanzie di alcun tipo: l'autore **non risponde** di alcun danno diretto o indiretto —
al veicolo, al sistema di infotainment, al software o ai dati — derivante
dall'installazione o dall'uso dell'app. L'uso è **interamente a tuo rischio**. Non
interagire con l'app durante la guida.

**La chiave è tua, e la fattura anche.** L'app usa la chiave API che le dai, e ogni
richiesta viene addebitata sul tuo account da OpenAI o xAI. L'autore non vede i tuoi
consumi e non c'entra nulla con la fatturazione. Usa una chiave con un limite di spesa,
e ricorda che una chiave dentro un file di testo su una chiavetta è una password dentro
un file di testo su una chiavetta: cancellala dopo averla importata.

**Un assistente non è una fonte.** Le risposte arrivano da un modello linguistico e
possono essere sbagliate con grande sicurezza. Non fare affidamento su quest'app per
cose che contano, e mai per qualcosa che riguardi la guida in sicurezza del veicolo.

**Microfono.** L'app registra solo mentre la tieni premuta o subito dopo che l'hai
aperta, se hai lasciato acceso l'ascolto automatico. Non esiste una parola di
attivazione e niente ascolta di nascosto.
