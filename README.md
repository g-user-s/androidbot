# alf

A Turkish voice assistant for a fixed home device: say **"hey alf"** with the screen off, hear
**"efendim"**, then give a command. It answers without a network, and reaches for a cloud model
only for what its fixed vocabulary cannot cover.

Target hardware is deliberately modest — a MediaTek MT8167 tablet, 2 GB of memory, Android 10,
rooted, running on battery. Nearly every design decision below follows from that; the reasoning
is written out in [docs/PLAN.md](docs/PLAN.md).

## How it works

```
microphone (16 kHz)
   → VAD gate                     nothing downstream runs while the room is quiet
   → MFCC + CMVN → DTW            compared against reference templates
   → "hey alf"?  → wake clip      "efendim" / "buradayım" / "dinliyorum"
   → command window (6 s)
       → local match?  → run the skill                    offline, instant
       → no match + online? → audio to Gemini             one round trip: speech + decision
       → otherwise → "bunu anlayamadım"
```

There is no on-device speech recognition. Offline, alf matches what it hears against a fixed list
of phrases; that is enough for commands and it is what this CPU can afford. Free-form speech is
understood only when a cloud model is reachable.

## Building

The Android modules are only included when asked for, so the pure Kotlin core builds and tests
anywhere — including a machine with no Android SDK.

```bash
./gradlew test                                  # the core: 180+ unit tests, no SDK needed
./gradlew -Palf.android=true :app:assembleDebug  # the apk, needs an Android SDK
```

A machine with `local.properties` (any Android Studio checkout) includes the Android modules
automatically. **If `:app` appears not to exist, the flag is missing** — see
`settings.gradle.kts`.

## Modules

| Module | Kind | What lives there |
|---|---|---|
| `domain/assistant` | JVM | `Skill`, `SkillDefinition`, the phrase catalog, `AssistantEngine` |
| `data/nlu` | JVM | Turkish text normalisation, rule based resolver, offline vocabulary |
| `data/dsp` | JVM | FFT, MFCC, DTW, VAD segmenter, phrase matcher, template file format |
| `data/sources` | JVM | Feed parsing (market, weather, RSS) and Turkish sentence building |
| `data/llm` | JVM | Gemini request building, response parsing, model fallback chain |
| `data/audio` | Android | Microphone, text to speech, wake clips |
| `data/skills` | Android | Skill executors, HTTP, settings |
| `app` | Android | Foreground service, control screen |
| `tools/voicegen` | JVM app | Generates reference templates and wake clips |

The split is the point: everything that can be tested without a device is in a JVM module, and
the Android modules are thin glue. Parsing a feed, building a Turkish sentence, choosing an alarm
hour, deciding which model to try — all of it runs in `./gradlew test`.

## The catalog is the single source

`SkillCatalog` in `domain/assistant` lists every phrase alf answers to. It feeds three consumers
that must never disagree:

1. the rule based resolver matches against it,
2. the offline vocabulary — and so the reference templates — is generated from it,
3. the Gemini function declarations are generated from it.

Adding a skill means adding a definition there plus an executor in `data/skills`. Nothing else.

Two flags on a definition are easy to confuse:

- `recognisableOffline` — can the phrase be *heard* without a network. False only for skills that
  take free text, which cannot be enumerated.
- `requiresNetwork` — can the answer be *produced* without a network. "hava nasıl" is recognised
  offline but cannot be answered, which is why alf says "şu an internetim yok" and not "bunu
  anlayamadım".

## Reference templates

The matcher compares what it hears against synthesised references. They are generated on a
workstation and shipped inside the apk — the device never contacts a speech service.

```bash
export ELEVENLABS_API_KEY=...
./gradlew :tools:voicegen:run --args="--voices <voice_id>,<voice_id>"
```

Writes `app/src/main/assets/templates.alf` and `app/src/main/res/raw/wake_*.wav`. Give it several
voices: each one is another example of the same words for the matcher to be close to. Without
these files the app falls back to synthesising with the device's own engine, which works but
sounds worse and makes the first start slow.

## Calibration

`MatcherTuning` in `app` holds the accept distance and margin. **The values there are starting
points, not measurements.** Normalised DTW distance means nothing in the abstract — it depends on
the microphone, the room and the voices the templates came from.

With `LOG_RANKINGS` on, every capture writes its closest phrases to logcat. Collect 50-100 real
"hey alf" utterances and a few hours of room noise, look at where the two distributions separate,
and set the thresholds there. The numbers to watch are false accepts per hour (target under 1)
and misses (target under 5%).

## Cloud model

Used only when the local matcher fails and the network is up. The captured audio goes up as it
is, with the skills offered as callable functions, so one request covers both understanding the
speech and deciding what to do.

Models are tried in order, newest first, dropping to the next when one reports an exhausted
quota; on a free tier that is the expected end of a model's day, not a fault. The list is
editable from the settings screen precisely so a new revision needs no new build. Current free
tier limits: <https://aistudio.google.com/rate-limit>.

The API key is stored in ordinary preferences. This device is rooted by design, so anything the
app can read root can read too; an encrypted store would add a library and imply a protection it
cannot give here. Issue a key used only for this assistant, and revoke it if the device is lost.

## Battery

There is no low-power path for the wake word on this hardware — the sound trigger DSP is closed
to third party apps, so the main CPU stays awake under a partial wake lock for as long as alf is
listening. Expect roughly three to four days on a tablet battery. `docs/PLAN.md` explains why,
and what was ruled out.
