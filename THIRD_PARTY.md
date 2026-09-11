# Third-party material

Minimal Pairs itself is MIT-licensed (see `LICENSE`). It builds on the
following material.

## Britfone 3.0.1

- What: British English (RP / Standard Southern British) pronunciation
  dictionary by José Llarena, https://github.com/JoseLlarena/Britfone
- Licence: MIT, copyright (c) 2017 Jose Llarena
- Use here: the sole pronunciation source. `scripts/build-catalog.py` reads it
  to find minimal pairs and to produce the IPA shown in the app.
- Vendored copy: `data/sources/britfone.main.3.0.1.csv`; the licence text is in
  `data/sources/britfone-README.md` (section "MIT License").

## FrequencyWords (hermitdave)

- What: word frequency lists derived from OpenSubtitles, by Hermit Dave,
  https://github.com/hermitdave/FrequencyWords
- Licence: MIT, copyright (c) 2016 Hermit Dave
- Use here: the `en_50k` list (2018 content) gives each word its frequency
  rank, from which the catalog derives the `high` / `mid` / `low` bands and
  decides which pairs are trainable.
- Vendored copy: `data/sources/en_50k.txt`; licence text in
  `data/sources/FrequencyWords-LICENSE`.

## Azure neural text-to-speech voices

- What: the word clips (`clips/<voice>/<word>.webm`) are synthesised with
  Microsoft Azure AI Speech, en-GB neural voices (Sonia, Libby, Hollie, Ryan,
  Thomas, Alfie), under the repository owner's own Azure subscription.
- Terms: the generated audio is the customer's output under Microsoft's product
  terms for Azure AI Speech. We are not aware of a restriction on distributing
  it. The clip pack is a GitHub Release asset (`clips.zip`), not part of the
  source tree, and is never committed.
- No Azure SDK is used: `scripts/render-clips.py` talks to the Speech service
  over HTTPS with the Python standard library only.

## AndroidX, Kotlin, Jetpack Compose, kotlinx-serialization

- Licence: Apache License 2.0 (Google, JetBrains and contributors).
- Use here: the app's only runtime dependencies. They are fetched by Gradle at
  build time and are not vendored.

## John Higgins' minimal-pair lists

- What: the well-known minimal-pair lists for English published online by
  John Higgins.
- Use here: consulted as a read-only reference to spot-check that the
  generated contrasts look right. Nothing was copied; the catalog is generated
  entirely from Britfone and the frequency list.
