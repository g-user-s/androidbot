# Working on alf

Read `README.md` first for the architecture and `docs/PLAN.md` for why each decision was made.
The points below are the ones that are easy to get wrong.

## Building

- `./gradlew test` builds and tests the pure Kotlin core and needs no Android SDK.
- The Android modules (`app`, `data/audio`, `data/skills`) are **only included when
  `-Palf.android=true` is passed** or a `local.properties` exists. If `:app` seems to be missing,
  that is why — see `settings.gradle.kts`. Do not "fix" this by making the inclusion
  unconditional; it is what keeps the core testable without a toolchain.
- Plugins are declared per module, never at the root. A plugin declared in the root build file
  lands on a parent classloader where the Kotlin Android plugin cannot see AGP.

## Where code belongs

Put logic in a JVM module and keep the Android modules as glue. Parsing, sentence building,
signal processing, model selection — all of it should be reachable from `./gradlew test`. If a
new piece of logic can only be exercised on a device, it is probably in the wrong module.

## The catalog

`SkillCatalog` (in `domain/assistant`) is the single source for every phrase alf answers to. It
drives the rule based resolver, the offline vocabulary and template generation, and the Gemini
function declarations. Add a skill there plus an executor in `data/skills`; do not restate a
skill's phrases, description or parameters anywhere else.

`recognisableOffline` means "can be heard offline"; `requiresNetwork` means "cannot be answered
offline". They are different and both matter to what alf says.

## House rules

- Offline first. Nothing the assistant needs at runtime may depend on a remote service — the
  reference templates and wake clips are generated ahead of time and shipped in the apk.
- Prefer no answer to a wrong one. Parsers return null rather than a default, the matcher refuses
  rather than guessing, and a failed fetch is spoken as a failure. A confidently wrong number
  read aloud is worse than "I could not get that".
- Turkish text goes through `TextNormalizer` (the Turkish locale changes what lowercasing does),
  and numbers read aloud go through the formatting in `data/sources` (a Turkish voice reads
  "6.705,58" correctly and "6705.58" as broken digits).
- Every dependency costs memory on a 2 GB device. There is no HTTP client library, no DI
  framework and no Compose here on purpose.
