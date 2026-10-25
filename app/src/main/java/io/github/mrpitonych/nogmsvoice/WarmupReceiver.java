package io.github.mrpitonych.nogmsvoice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.vosk.Model;

/**
 * Loads the model ahead of the first request, e.g. right after an install:
 * {@code adb shell am broadcast -n io.github.mrpitonych.nogmsvoice/.WarmupReceiver}
 * The broadcast is held open until the model is ready, so the command returns only then and Android does
 * not drop the otherwise idle process halfway through the load.
 */
public class WarmupReceiver extends BroadcastReceiver {
    private static final long WAIT_MS = 9500;   // goAsync() must finish within 10 s, or the receiver counts as hung

    @Override
    public void onReceive(Context context, Intent intent) {
        final PendingResult result = goAsync();
        ModelHolder.ensureLoading(context);
        new Thread(() -> {
            Model model = ModelHolder.await(WAIT_MS, () -> false);
            String state = model != null ? "model ready"
                    : ModelHolder.failure() != null ? "model failed to load" : "model still loading";
            Log.i(VoskRecognitionService.TAG, "warmup: " + state);
            result.finish();
        }, "NoGmsVoice-warmup").start();
    }
}
