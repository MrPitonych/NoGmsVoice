# NoGmsVoice

[По-русски](README.ru.md)

Offline speech recognition, Russian or English, for Android TVs that have no Google services.

NoGmsVoice is an Android speech recognition service (`android.speech.RecognitionService`) built on
[Vosk](https://alphacephei.com/vosk/), with a small Vosk model packed into the APK. It comes in two builds,
`ru` with the Russian model and `en` with the US English one. Once it is set as the system's recognizer,
the microphone button should work in any app that uses the standard `SpeechRecognizer`; confirmed in
Lampa, Zona and TizenTube Cobalt (a YouTube client). Nothing leaves the TV: no internet permission, no
accounts, no telemetry. The only runtime permission it needs is the microphone.

It was written for a China-market Xiaomi TV (S55 Mini LED 2024, MiTV-MFTR0, Android 11): its own assistant
understands only Chinese, and Google's recognizer cannot be installed on it. It is meant for ARM Android
TVs and boxes on Android 8.0 or newer that have no recognizer of their own. So far it has been tried on
that one TV; see [Tested on](#tested-on).

## What it does and does not do

- **Works:** the microphone inside apps that use `SpeechRecognizer`. Confirmed in Lampa, Zona and TizenTube
  Cobalt. Partial text appears while you speak (in apps that ask for it), and the app's level meter moves.
- **One language per build.** The `ru` build carries `vosk-model-small-ru-0.22`, the `en` build
  `vosk-model-small-en-us-0.15`. The language an app asks for is ignored: install the build you need.
- **Not an assistant.** Pressing the remote's voice key alone starts nothing: select the microphone in the
  app first. There is no wake word.
- **Not supported yet:** apps that launch the `RECOGNIZE_SPEECH` activity instead of binding the service.
  They say "no app found" or do nothing.
- **Quality** is that of the small Vosk models: fine for short search phrases, weaker on long dictation,
  and names or foreign titles outside the model's vocabulary come out wrong. Word error rates published
  with the models: Russian 11.8% on crowd recordings, 21.3% far-field and 22.7% on audiobooks; English
  9.9% on LibriSpeech test-clean and 10.4% on TED-LIUM. Only the Russian model has been used on a TV.

## Tested on

The `ru` build: Xiaomi TV S55 Mini LED 2024 (MiTV-MFTR0), China firmware V816.0.16.0.RFTMATV, Android 11,
32-bit ARM, with the stock Bluetooth voice remote and Xiaomi's own assistant (`com.xiaomi.voicecontrol`)
disabled. Not tried with that assistant enabled. The `en` build is the same code with the English model and
has not been tried on a device yet.

On that TV the remote's microphone streams only while the voice key is held, so: select the microphone in
the app, hold the key on the remote, say the phrase, release. The service treats the end of the stream as
the end of the phrase.

Not tested: USB microphones, third-party remotes, Xiaomi's 2025 models with built-in microphones, other
brands. Whether it works on your TV or not, please open an issue with the TV model, the firmware version
and the [log](#troubleshooting). Keep IP addresses, Wi-Fi names and serial numbers out of it.

## Install

Requirements:

- An ARM TV or box on Android 8.0 or newer. `adb shell getprop ro.product.cpu.abilist` must list
  `armeabi-v7a`, or the install fails with `INSTALL_FAILED_NO_MATCHING_ABIS`.
- A microphone: a voice remote or a USB one.
- About 150 MB free (125 MB for the `en` build): the APK plus the model it unpacks.
- ADB on a computer on the same network ([platform-tools](https://developer.android.com/tools/releases/platform-tools)).
  On the Xiaomi: Settings → Accounts and Security → ADB debugging (on the S55 firmware it switches itself
  off at every reboot; the settings below survive reboots). On other TVs: Developer options (select the
  build number seven times), then USB or network debugging. The first `adb connect` asks for confirmation
  on the TV screen.

Steps:

1. Download `NoGmsVoice-<version>-ru.apk` or `NoGmsVoice-<version>-en.apk` from
   [Releases](https://github.com/MrPitonych/NoGmsVoice/releases) and compare its SHA-256 with the one in
   the release notes (`sha256sum <file>` on Linux, `shasum -a 256 <file>` on macOS,
   `certutil -hashfile <file> SHA256` on Windows).
2. Note what the TV uses now, so you can put it back on uninstall (empty on the S55):

   ```
   adb connect <tv ip>
   adb shell settings get secure voice_recognition_service
   ```

3. Install, grant it the microphone, and make it the system's recognizer:

   ```
   adb install -r NoGmsVoice-<version>-ru.apk
   adb shell pm grant io.github.mrpitonych.nogmsvoice android.permission.RECORD_AUDIO
   adb shell settings put secure voice_recognition_service io.github.mrpitonych.nogmsvoice/.VoskRecognitionService
   adb shell am broadcast -n io.github.mrpitonych.nogmsvoice/.WarmupReceiver
   ```

There is no icon or screen: it is a background service, so nothing new appears in the app list. The last
line is optional: it unpacks and loads the model right away instead of on the first request, and returns
once the model is ready, or after 10 s at most (loading then goes on in the background). Later, the first
request after the service process starts (after a reboot, or after Android has stopped it for being idle)
takes a few seconds longer while the model loads; on the Xiaomi that is 3-5 s. What you say meanwhile is
kept and recognized once it is ready.

To switch languages, install the other build the same way: it replaces the installed one, and the
permission and the setting stay. An APK signed with a different key cannot replace the installed one
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`): a GitHub release over your own build, your own build over a
release, or a CI artifact over either. Uninstall first with `adb uninstall io.github.mrpitonych.nogmsvoice`,
install, then run the three `adb shell` lines again (uninstalling drops the permission and may clear the
setting).

## Use

In the app, select the microphone icon. With a Xiaomi remote: hold the voice key within 8 s, say the
phrase, release. A pause of a couple of seconds with the key still held ends the phrase too. On a TV whose
microphone is always on, just speak: the phrase ends a couple of seconds after the last word, or after
20 s at most. This case is handled in the code but has not been tried on such a TV yet.

## Troubleshooting

```
adb shell logcat -d -v time NoGmsVoice:V VoskAPI:W AndroidRuntime:E '*:S'
```

The log shows the whole path of a request, including the recognized text:

| Line | Meaning |
|---|---|
| `listening for uid N` (`, partial results wanted` when the app asked for them) | an app asked for recognition |
| `model unpacked in N ms` | the first load after every install or update: the model files were copied out of the APK |
| `model loaded in N ms` | every start of the service process (after a reboot, or after Android stopped it for being idle) |
| `model load failed` | the cause follows; the app gets `ERROR_SERVER` |
| `warmup: model ready`, `warmup: model still loading`, `warmup: model failed to load` | how the warmup broadcast ended |
| `audio started N ms after the request` | the first audio arrived from the microphone |
| `decoding N ms captured while the model loaded` | the first request after a process start: speech from before the model was ready |
| `no audio: the microphone sent nothing` | nothing in 8 s. On a Xiaomi remote: the voice key was not held |
| `no speech in N ms of audio` | audio arrived but contained no words |
| `stream stopped after N ms of audio` | the remote's key was released: the phrase is done |
| `silence after N ms of audio` | nothing more was said after the last words: the phrase is done |
| `utterance cap reached` | 20 s of audio: the phrase is cut off there |
| `stop requested` | the app ended the phrase itself |
| `cancelled` | the app cancelled the request (it gets no result) |
| `result: "..."` | what the app received; empty means `ERROR_NO_MATCH` |

Errors the app sees: `ERROR_AUDIO` (the microphone could not be opened, or stopped delivering: was
`pm grant` run?), `ERROR_SPEECH_TIMEOUT` (no audio or no words), `ERROR_NO_MATCH` (nothing recognized),
`ERROR_SERVER` (the model failed to load: see the log). `ERROR_INSUFFICIENT_PERMISSIONS` means the app itself
has no microphone permission: `adb shell pm grant <app package> android.permission.RECORD_AUDIO`.

Check the setting and the recognizers the system knows about:

```
adb shell settings get secure voice_recognition_service
adb shell cmd package query-services --brief -a android.speech.RecognitionService
```

If the setting has changed back by itself, the TV's own assistant has probably reclaimed it; set it again.

## Uninstall

```
adb shell settings delete secure voice_recognition_service
adb uninstall io.github.mrpitonych.nogmsvoice
```

If the setting held a value before the install, put that value back with `settings put` instead of
deleting it.

## Build from source

You need JDK 17 and an Android SDK with platform 34 and build-tools 34.0.0 (`ANDROID_HOME` set, or a
`local.properties` with `sdk.dir`). `fetch-model.sh` needs `sh`, `curl` and `unzip`: on Windows run it
from Git Bash or WSL, then build with `gradlew.bat`.

```
./fetch-model.sh all                    # or ru, or en: downloads the models (40-45 MB each) and checks their md5
./gradlew lintRuRelease lintEnRelease assembleRelease
```

The APKs are written to `app/build/outputs/apk/<language>/release/NoGmsVoice-<version>-<language>.apk`,
45-50 MB each. Without a `keystore.properties` (see `keystore.properties.example`) they are signed with the
debug key, which is fine for your own TV. The GitHub Actions workflow builds and lints both languages the
same way on every push to `main`, on tags and on pull requests, and fails if an APK asks for any
permission beyond the two below.

Why some things are the way they are:

- `QUERY_ALL_PACKAGES`: Android 11 (seen there; later versions are documented to behave the same) checks
  the calling app's microphone permission through the recognizer, so the recognizer has to be able to see
  every app. Without it every caller gets `ERROR_INSUFFICIENT_PERMISSIONS`.
- No `INTERNET` permission: that is the point of the project.
- `armeabi-v7a` only: 32-bit ARM code runs on most ARM TVs and boxes, 64-bit ones included (the Xiaomi is
  32-bit only); the other ABIs would add about 10 MB to the APK.
- No R8 (code shrinking): JNA finds Vosk's classes by reflection.
- One model per build instead of a chooser: no download code, no internet permission, and the APK works on
  the first request. Another language is a third flavour (`productFlavors`) in `app/build.gradle` with its
  assets line, its `service_label` strings, its line in `fetch-model.sh` and its lint task in the workflow.
- A different model for a language: its name and md5 (from Alpha Cephei's `model-list.json`) in
  `fetch-model.sh`, which replaces the old files on its next run, plus the model's name and figures in this
  file. The copy unpacked on the TV is replaced automatically at the next install.

## Third-party components

- [Vosk](https://github.com/alphacep/vosk-api) 0.3.75, Apache License 2.0
- [vosk-model-small-ru-0.22 and vosk-model-small-en-us-0.15](https://alphacephei.com/vosk/models) by Alpha
  Cephei, Apache License 2.0
- [JNA](https://github.com/java-native-access/jna) 5.18.1, Apache License 2.0 (dual-licensed with LGPL 2.1)

## License

[MIT](LICENSE).
