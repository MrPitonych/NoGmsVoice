# NoGmsVoice

An offline `RecognitionService` (Vosk) for Android TVs without Google services, built in two language
flavours, `ru` and `en`. Plain Java, one Gradle module, no unit tests: lint, the build and a run on a real
TV are the checks.

`README.md` is the user documentation and the source for: what the service does and does not do (the gaps
listed there are known, not bugs), the install and troubleshooting commands, the log-line table, and why
`QUERY_ALL_PACKAGES`, `armeabi-v7a` only and no R8. Read the relevant section before changing any of these.

## Build and check

    ./fetch-model.sh all
    JAVA_HOME=<jdk 17> ANDROID_HOME=<sdk with platform 34, build-tools 34.0.0> ./gradlew --no-daemon lintRuRelease lintEnRelease assembleRelease

- Lint fails on warnings too (`warningsAsErrors`). Suppress a check only with a comment saying why.
- `fetch-model.sh` leaves a language alone when its model is in place, and replaces it when the name in the
  script changed; nothing to delete by hand.
- Without `keystore.properties` the APKs are debug-signed and cannot update a release-signed install:
  uninstall first, then grant the permission and set the recognizer again.

## Testing on a TV

No emulator reproduces the TV's microphone path. After a change in behaviour:

1. Install the APK, grant `RECORD_AUDIO`, set `voice_recognition_service` and send the warmup broadcast,
   as in the README's "Install".
2. Ask a person to press the microphone in a real app (Lampa or Zona; TizenTube records the microphone
   itself and never calls the service) and speak. An injected key is not a test: on the Xiaomi the
   remote's voice key is what opens the audio stream.
3. Read `adb shell logcat -d -v time NoGmsVoice:V VoskAPI:W AndroidRuntime:E '*:S'`. A good request shows
   `listening`, `audio started`, one end-of-phrase line (`stream stopped`, `silence after`,
   `utterance cap reached` or `stop requested`) and `result: "..."`.
4. Ask for both endings: releasing the remote's key (`stream stopped`), and a pause with the key held or an
   always-on microphone (`silence after`).

Every install re-unpacks the model at its next load (`model unpacked` in the log; the stamp in
`files/model/.stamp` is the APK's `lastUpdateTime`), so nothing needs bumping after a model change. After
a change to the model path or the flavours, build both flavours and, if a TV is at hand, install `en` as
well. Without a person's run on a TV, report a behaviour change as built but not tested on a device.

## `Session` invariants (`VoskRecognitionService.java`)

- Its constants are the timings, tuned on the Xiaomi S55 with its remote. Change one only with a run on a
  real TV, not on a guess.
- `END_SILENCE_SAMPLES` and `NO_SPEECH_SAMPLES` count audio handed to the decoder (`decodedSamples`), not
  wall-clock time: decoding lags the stream, most of all right after the model loads. Keep them in samples.
- Audio captured before the model is loaded is kept (up to 20 s) and decoded in 100 ms slices, so Vosk's
  segment ends fall where they would have live. Do not feed it in one call.
- With `EXTRA_PARTIAL_RESULTS`, the partial is re-sent every 1.5 s even when unchanged: some speech
  libraries time out between partials.
- Every callback goes through `deliver()`, which skips it once the request is cancelled: a late result or
  error would land on the client's next request and make the framework drop it. Keep it that way.
- The audio source is `VOICE_RECOGNITION` unless the global setting `nogmsvoice_audio_source` names
  another one (README, "Audio source"); the log says which, and `capture silenced by the system` when
  Android feeds the capture zeros. Settings.Global was chosen because any app can read a custom key there
  and `adb shell settings` can write it without an activity.

## Rules

- **Offline is the product.** No `INTERNET` permission, no network code, no analytics, no background work
  outside a request. The APK asks for `RECORD_AUDIO` and `QUERY_ALL_PACKAGES`, nothing more; a library can
  add permissions, and CI fails on any other permission in the built APKs.
- **Keep it a `RecognitionService`.** A setup activity (microphone permission, status) is welcome if it
  adds no permissions; a `RECOGNIZE_SPEECH` activity is a known gap and a reasonable addition.
- **One model per flavour, nothing downloaded at runtime.**
  - New language `xx`: in `app/build.gradle`, a flavour (`dimension = 'language'; versionNameSuffix = '-xx'`)
    and its `xx.assets.srcDirs` line (without it the APK builds with no model and fails on the first
    request); in `fetch-model.sh`, a variable and a case branch, plus the `all` branch and the usage text;
    `service_label` in `app/src/xx/res/values/strings.xml` and `values-ru/strings.xml`; `lintXxRelease` in
    `.github/workflows/build.yml` and in the build command above; both READMEs (the list of builds, the
    model name, its quality figures, "Third-party components").
  - New model for a language: its name and md5 in `fetch-model.sh` (the md5 from Alpha Cephei's
    `model-list.json`, not from your download); in both READMEs, its name and its quality figures. The
    figures come from the README inside the model zip: the `%WER` lines (golos crowd, far-field,
    audiobooks) for `ru`, the `Accuracy:` line for `en`.
- **`armeabi-v7a` only, no R8, legacy JNI packaging:** the reasons are in `app/build.gradle` and the
  README; do not change any of them without solving what they solve.
- **Logging:** tag `NoGmsVoice`. The recognized text is logged on purpose (the only way to debug on a TV);
  it stays on the device. The log lines in the READMEs' troubleshooting tables are code text and the error
  list there follows the code: change them together with it.
- **Both READMEs change together.** `README.md` and `README.ru.md` say the same things. A dependency bump
  also changes their "Third-party components" versions.
- **Privacy:** never write a TV's IP address, Wi-Fi name, serial number, Android ID or account into code,
  docs, commits, issues, PRs or release notes. Use `<tv ip>` (`<ip телевизора>` in `README.ru.md`).
- **Commits:** `area: what changed`, in English, one topic per commit.
- **Releases:** bump `versionCode` and `versionName`, tag `vX.Y.Z`, build both flavours with
  `keystore.properties`, attach `NoGmsVoice-X.Y.Z-ru.apk` and `NoGmsVoice-X.Y.Z-en.apk`, and put their
  SHA-256 and the "tested on" line in the release notes. Never attach the CI artifacts: they are
  debug-signed.

## Not in git

- `model/` (written by `fetch-model.sh`), `keystore.properties` and the keystore. Never commit them.
- `CLAUDE.local.md`: machine-specific notes, such as how this computer reaches its TV. Create one for yours.
