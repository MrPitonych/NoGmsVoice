package io.github.mrpitonych.nogmsvoice;

import android.annotation.TargetApi;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline speech recognition for Android TVs without Google services, built on Vosk.
 *
 * <p>Apps reach it through the platform's SpeechRecognizer once this service is the system's
 * voice_recognition_service. One request is one utterance: the service polls the microphone without
 * blocking and hands Vosk 100 ms of audio at a time, sending partial results as the text changes when the
 * client asked for them. The utterance ends when the client stops listening, when the audio stream stops
 * (Xiaomi's Bluetooth remote streams only while its voice key is held), when Vosk's endpointer has closed a
 * segment and nothing more is said for a moment (microphones that stream all the time), or at a 20 s cap.
 * Silence is measured in audio handed to the decoder, not in wall-clock time, because decoding may lag the
 * stream. Audio that arrives while the model is still loading is kept and decoded once it is ready.
 *
 * <p>The language is that of the model built into this flavour (Russian or English), whatever the client
 * asks for: no other model is in the APK. The microphone is opened on the VOICE_RECOGNITION source, which
 * is where TVs route a voice remote; the global setting {@value #AUDIO_SOURCE_SETTING} picks another one
 * for TVs whose firmware silences that source for third-party apps.
 */
public class VoskRecognitionService extends RecognitionService {
    static final String TAG = "NoGmsVoice";
    /** adb shell settings put global nogmsvoice_audio_source mic (voice_recognition when unset). */
    static final String AUDIO_SOURCE_SETTING = "nogmsvoice_audio_source";
    /** Bounded because endSession() runs on the main thread. A session notices a cancel within a chunk. */
    private static final long SESSION_END_TIMEOUT_MS = 2000;

    private Session session;    // the latest request, possibly finished; main thread only

    @Override
    public void onCreate() {
        super.onCreate();
        ModelHolder.ensureLoading(this);    // the first request usually follows the bind within a second
    }

    @Override
    protected void onStartListening(Intent intent, Callback callback) {
        endSession();                       // a finished request's thread may still be releasing the microphone
        ModelHolder.ensureLoading(this);    // no-op once loaded; after a failed load, this is the retry
        boolean wantPartials = intent != null
                && intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        String sourceName = audioSourceName();
        Log.i(TAG, "listening for uid " + callback.getCallingUid() + ", source " + sourceName
                + (wantPartials ? ", partial results wanted" : ""));
        session = new Session(callback, wantPartials, audioSource(sourceName));
        session.start();
    }

    private String audioSourceName() {
        String name = Settings.Global.getString(getContentResolver(), AUDIO_SOURCE_SETTING);
        return name == null || name.isEmpty() ? "voice_recognition" : name;
    }

    /** The MediaRecorder.AudioSource constant named by the setting, in lower case; unknown names fall back. */
    private static int audioSource(String name) {
        switch (name) {
            case "mic": return MediaRecorder.AudioSource.MIC;
            case "default": return MediaRecorder.AudioSource.DEFAULT;
            case "camcorder": return MediaRecorder.AudioSource.CAMCORDER;
            case "unprocessed": return MediaRecorder.AudioSource.UNPROCESSED;
            case "voice_communication": return MediaRecorder.AudioSource.VOICE_COMMUNICATION;
            case "voice_recognition": return MediaRecorder.AudioSource.VOICE_RECOGNITION;
            default:
                Log.w(TAG, "unknown " + AUDIO_SOURCE_SETTING + " \"" + name + "\", using voice_recognition");
                return MediaRecorder.AudioSource.VOICE_RECOGNITION;
        }
    }

    @Override
    protected void onStopListening(Callback callback) {
        Log.i(TAG, "stop requested");
        if (session != null) session.stopRequested = true;
    }

    @Override
    protected void onCancel(Callback callback) {
        Log.i(TAG, "cancelled");
        endSession();
    }

    @Override
    public void onDestroy() {
        endSession();
        super.onDestroy();
    }

    /**
     * Cancels the running session and waits for its thread, so the microphone is released before the next
     * session opens it: the framework takes the next request as soon as a result or error is sent, while
     * the thread may still be cleaning up.
     */
    private void endSession() {
        Session ending = session;
        session = null;
        if (ending == null) return;
        ending.cancel();
        try {
            ending.join(SESSION_END_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (ending.isAlive()) Log.w(TAG, "previous session still running after " + SESSION_END_TIMEOUT_MS + " ms");
    }

    /**
     * One request on its own thread: opens the microphone, decodes as the audio comes in, and reports the
     * result or an error.
     */
    private static final class Session extends Thread {
        // Tuned on the Xiaomi S55 with its remote: change one only after a run on a real TV.
        // _MS limits are wall-clock time; _SAMPLES limits count audio handed to Vosk, which can lag the stream.
        private static final int SAMPLE_RATE = 16000;
        private static final int CHUNK_SAMPLES = SAMPLE_RATE / 10;                       // 100 ms
        private static final int RECORD_BUFFER_BYTES = SAMPLE_RATE * Short.BYTES * 4;  // 4 s, while decoding lags
        private static final int MAX_KEPT_SAMPLES = SAMPLE_RATE * 20;                    // as long as MAX_UTTERANCE_MS
        private static final long POLL_INTERVAL_MS = 20;
        private static final long NO_AUDIO_TIMEOUT_MS = 8000;       // nothing arrived: the remote's key was not pressed
        private static final long STREAM_GAP_MS = 800;              // the stream stopped: the key was released
        private static final long MAX_UTTERANCE_MS = 20000;         // from the first audio
        private static final long MODEL_WAIT_MS = 15000;            // after capture ends, for a model still loading
        private static final long PARTIAL_REPEAT_MS = 1500;         // some clients time out between partials
        private static final long NO_SPEECH_SAMPLES = SAMPLE_RATE * 6;            // audio arrived, no words in it
        private static final long END_SILENCE_SAMPLES = SAMPLE_RATE * 1200 / 1000; // closed segment, then no words

        private final Callback callback;
        private final boolean wantPartials;
        private final int audioSource;

        // Set from the main thread, where RecognitionService delivers its calls; every other field belongs
        // to this thread. Once cancelled is set, no callback may go out: the client would take it as the
        // answer to its next request, and a result or error would also make the framework drop that
        // request. callbackLock makes the check and the call one step, so a cancel cannot slip in between.
        private final Object callbackLock = new Object();
        private volatile boolean cancelled;
        volatile boolean stopRequested;

        private Recognizer recognizer;        // null until the model is loaded
        private long decodedSamples;          // audio handed to Vosk: the clock of the _SAMPLES limits
        private String closedText = "";       // text of the segments Vosk closed at pauses
        private String partialText = "";      // text of the segment Vosk is still decoding
        private long closedAtSample = -1;     // decodedSamples when the last segment closed; -1 once more words follow

        private short[] kept;                 // audio captured while the model was loading
        private int keptLength;

        private String sentPartial = "";      // the last partial result sent, and when (uptime ms)
        private long sentPartialAt;
        private boolean beginningSent;

        Session(Callback callback, boolean wantPartials, int audioSource) {
            super("NoGmsVoice-session");
            this.callback = callback;
            this.wantPartials = wantPartials;
            this.audioSource = audioSource;
        }

        void cancel() {
            synchronized (callbackLock) {
                cancelled = true;
            }
        }

        @Override
        public void run() {
            long startedAt = SystemClock.uptimeMillis();
            AudioRecord record = null;
            try {
                Exception loadFailure = ModelHolder.failure();
                if (loadFailure != null) throw new IOException("model failed to load", loadFailure);
                record = openRecord(audioSource);
                if (record == null) {
                    error(SpeechRecognizer.ERROR_AUDIO);
                    return;
                }
                watchForSilencing(record);
                deliver(() -> callback.readyForSpeech(new Bundle()));
                short[] buffer = new short[CHUNK_SAMPLES];
                int fill = 0;
                long firstAudioAt = 0;
                long lastAudioAt = 0;
                while (!cancelled) {
                    // Non-blocking: the remote sends nothing while its key is up, and the loop has to notice that.
                    int read = record.read(buffer, fill, CHUNK_SAMPLES - fill, AudioRecord.READ_NON_BLOCKING);
                    if (read < 0) {
                        Log.e(TAG, "read failed: " + read);
                        error(SpeechRecognizer.ERROR_AUDIO);
                        return;
                    }
                    long now = SystemClock.uptimeMillis();
                    if (read > 0) {
                        if (firstAudioAt == 0) {
                            firstAudioAt = now;
                            Log.i(TAG, "audio started " + (now - startedAt) + " ms after the request");
                        }
                        lastAudioAt = now;
                        fill += read;
                        if (fill == CHUNK_SAMPLES) {
                            fill = 0;
                            deliver(() -> callback.rmsChanged(rmsDb(buffer, CHUNK_SAMPLES)));
                            decodeLive(buffer, CHUNK_SAMPLES);
                            if (closedAtSample >= 0 && decodedSamples - closedAtSample >= END_SILENCE_SAMPLES) {
                                Log.i(TAG, "silence after " + toMs(closedAtSample) + " ms of audio");
                                break;
                            }
                            if (closedText.isEmpty() && partialText.isEmpty()
                                    && decodedSamples >= NO_SPEECH_SAMPLES) {
                                Log.i(TAG, "no speech in " + toMs(decodedSamples) + " ms of audio");
                                error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
                                return;
                            }
                        }
                        if (now - firstAudioAt >= MAX_UTTERANCE_MS) {
                            Log.i(TAG, "utterance cap reached");
                            break;
                        }
                    } else if (stopRequested) {
                        break;      // the client ended the phrase, and what the microphone had captured is read
                    } else if (firstAudioAt == 0 && now - startedAt >= NO_AUDIO_TIMEOUT_MS) {
                        Log.i(TAG, "no audio: the microphone sent nothing");
                        error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
                        return;
                    } else if (firstAudioAt != 0 && now - lastAudioAt >= STREAM_GAP_MS) {
                        Log.i(TAG, "stream stopped after " + (lastAudioAt - firstAudioAt) + " ms of audio");
                        break;
                    } else {
                        SystemClock.sleep(POLL_INTERVAL_MS);
                    }
                }
                if (cancelled) return;
                record.stop();              // capture is over; finishing may still wait for the model
                if (firstAudioAt == 0) {    // stopListening came before any audio
                    error(SpeechRecognizer.ERROR_NO_MATCH);
                    return;
                }
                finishUtterance(buffer, fill);
            } catch (RemoteException e) {
                Log.w(TAG, "client is gone", e);
            } catch (IOException | RuntimeException e) {
                // RuntimeException too: a native (JNA) failure or a bug ends this request, not the process
                // and its loaded model.
                Log.e(TAG, "request failed", e);
                error(SpeechRecognizer.ERROR_SERVER);
            } finally {
                if (recognizer != null) recognizer.close();
                if (record != null) record.release();   // release() stops a running capture itself
                kept = null;
            }
        }

        private static AudioRecord openRecord(int audioSource) {
            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) {
                Log.e(TAG, "no 16 kHz mono input: " + min);
                return null;
            }
            AudioRecord record;
            try {
                record = new AudioRecord(audioSource, SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(min, RECORD_BUFFER_BYTES));
            } catch (IllegalArgumentException | SecurityException e) {
                Log.e(TAG, "cannot open the microphone", e);
                return null;
            }
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "microphone not initialised");
                record.release();
                return null;
            }
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "recording did not start");
                record.release();
                return null;
            }
            return record;
        }

        /**
         * Logs it when the system feeds this capture silence instead of the microphone: Android does that when
         * an app with a higher capture priority is recording, which some TV firmwares do on the
         * VOICE_RECOGNITION source. The record then delivers zeros, and the user sees empty results.
         */
        @TargetApi(Build.VERSION_CODES.Q)   // the callback's own method is outside the guard below, as lint sees it
        private static void watchForSilencing(AudioRecord record) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
            record.registerAudioRecordingCallback(Runnable::run, new AudioManager.AudioRecordingCallback() {
                private boolean reported;

                @Override
                public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                    for (AudioRecordingConfiguration config : configs) {
                        if (config.isClientSilenced() && !reported) {
                            reported = true;
                            Log.w(TAG, "capture silenced by the system: another app has priority on this audio source;"
                                    + " try: settings put global " + AUDIO_SOURCE_SETTING + " mic");
                        }
                    }
                }
            });
        }

        /**
         * Decodes one live chunk (keeps it while the model is loading), then sends a partial result if the
         * text changed or PARTIAL_REPEAT_MS has passed.
         */
        private void decodeLive(short[] data, int length) throws RemoteException, IOException {
            if (recognizer == null) {
                Model model = ModelHolder.peek();
                if (model == null) {
                    Exception loadFailure = ModelHolder.failure();
                    if (loadFailure != null) throw new IOException("model failed to load", loadFailure);
                    keep(data, length);
                    return;
                }
                startRecognizer(model);
            }
            decode(data, length);
            partialText = field(recognizer.getPartialResult(), "partial");
            if (!partialText.isEmpty()) {
                closedAtSample = -1;
                sendBeginningOfSpeech();
            }
            if (!wantPartials) return;
            // Clients show each partial in place of the last, so it carries the closed segments too.
            String text = joinWords(closedText, partialText);
            if (text.isEmpty()) return;
            long now = SystemClock.uptimeMillis();
            if (!text.equals(sentPartial) || now - sentPartialAt >= PARTIAL_REPEAT_MS) {
                deliver(() -> callback.partialResults(resultBundle(text)));
                sentPartial = text;
                sentPartialAt = now;
            }
        }

        /** Hands audio to Vosk and keeps the text of a segment its endpointer closes at a pause. */
        private void decode(short[] data, int length) throws RemoteException {
            boolean closed = recognizer.acceptWaveForm(data, length);
            decodedSamples += length;
            if (!closed) return;
            // The speaker may go on (the remote's key may still be held), so keep the text and carry on; the
            // loop ends the utterance if nothing follows within END_SILENCE_SAMPLES.
            String text = field(recognizer.getResult(), "text");
            if (!text.isEmpty()) {
                closedText = joinWords(closedText, text);
                sendBeginningOfSpeech();
                closedAtSample = decodedSamples;
            } else if (!closedText.isEmpty() && closedAtSample < 0) {
                closedAtSample = decodedSamples;    // words, then a noise Vosk dropped: restart the countdown
            }
        }

        private void startRecognizer(Model model) throws IOException, RemoteException {
            recognizer = new Recognizer(model, SAMPLE_RATE);
            decodeKept();
        }

        /** Decodes the kept audio in live-sized chunks, so segment ends fall where they would have live. */
        private void decodeKept() throws RemoteException {
            if (keptLength == 0) return;
            Log.i(TAG, "decoding " + toMs(keptLength) + " ms captured while the model loaded");
            short[] slice = new short[CHUNK_SAMPLES];
            for (int offset = 0; offset < keptLength && !cancelled; offset += CHUNK_SAMPLES) {
                int n = Math.min(CHUNK_SAMPLES, keptLength - offset);
                System.arraycopy(kept, offset, slice, 0, n);
                decode(slice, n);
            }
            kept = null;
            keptLength = 0;
        }

        private void keep(short[] data, int length) {
            if (length == 0) return;
            if (kept == null) kept = new short[MAX_KEPT_SAMPLES];
            if (keptLength + length > kept.length) return;   // past that the 20 s cap has ended the utterance anyway
            System.arraycopy(data, 0, kept, keptLength, length);
            keptLength += length;
        }

        private void sendBeginningOfSpeech() throws RemoteException {
            if (beginningSent) return;
            beginningSent = true;
            deliver(callback::beginningOfSpeech);
        }

        private void finishUtterance(short[] rest, int restLength) throws RemoteException, IOException {
            if (recognizer == null) {
                keep(rest, restLength);
                Model model = ModelHolder.await(MODEL_WAIT_MS, () -> cancelled);
                if (cancelled) return;
                if (model == null) {
                    Exception loadFailure = ModelHolder.failure();
                    throw new IOException(loadFailure != null ? "model failed to load"
                            : "model not loaded within " + MODEL_WAIT_MS + " ms", loadFailure);
                }
                startRecognizer(model);
            } else if (restLength > 0) {
                decode(rest, restLength);
            }
            if (cancelled) return;
            String text = joinWords(closedText, field(recognizer.getFinalResult(), "text"));
            // The text is logged on purpose: on a TV the log is the only way to debug, and it stays on the device.
            Log.i(TAG, "result: \"" + text + "\"");
            if (text.isEmpty()) {
                error(SpeechRecognizer.ERROR_NO_MATCH);
                return;
            }
            // endOfSpeech goes out with the result: until the kept audio is decoded nothing is known about
            // the speech, and clients that stop a timer at endOfSpeech should not then wait for the model.
            sendBeginningOfSpeech();
            deliver(() -> {
                callback.endOfSpeech();
                callback.results(resultBundle(text));
            });
        }

        /** Reports an error, closing the speech first if it was opened. Never throws: run()'s catch blocks call it. */
        private void error(int code) {
            try {
                deliver(() -> {
                    if (beginningSent) callback.endOfSpeech();
                    callback.error(code);
                });
            } catch (RemoteException e) {
                Log.w(TAG, "client is gone", e);
            }
        }

        /** Runs one callback unless the request was cancelled; cancel() sets the flag under the same lock. */
        private void deliver(Delivery delivery) throws RemoteException {
            synchronized (callbackLock) {
                if (cancelled) return;
                delivery.run();
            }
        }

        private interface Delivery {
            void run() throws RemoteException;
        }

        private static long toMs(long samples) {
            return samples * 1000 / SAMPLE_RATE;
        }

        private static Bundle resultBundle(String text) {
            ArrayList<String> list = new ArrayList<>(1);
            list.add(text);
            Bundle bundle = new Bundle();
            bundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, list);
            // Vosk gives no score and some clients read one: one result, full confidence.
            bundle.putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, new float[] {1f});
            return bundle;
        }

        private static String field(String json, String key) {
            try {
                return new JSONObject(json).optString(key, "").trim();
            } catch (JSONException e) {
                Log.w(TAG, "unexpected Vosk output: " + json);
                return "";
            }
        }

        private static String joinWords(String head, String tail) {
            if (head.isEmpty()) return tail;
            if (tail.isEmpty()) return head;
            return head + " " + tail;
        }

        /** The chunk's loudness on the scale of Google's recognizer, which clients' meters expect: about -2 to 10. */
        private static float rmsDb(short[] buffer, int length) {
            double sum = 0;
            for (int i = 0; i < length; i++) sum += (double) buffer[i] * buffer[i];
            double db = 20 * Math.log10(Math.max(Math.sqrt(sum / length), 1) / 32768.0);
            return (float) Math.max(-2, Math.min(10, (db + 60) / 5));
        }
    }
}
