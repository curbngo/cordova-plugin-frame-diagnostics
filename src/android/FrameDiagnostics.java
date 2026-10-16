package com.curbngo.framediagnostics;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;
import android.webkit.WebView;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Samples the live WebView. Does not reload, hide, pause, or replace it.
 * PixelCopy results are recorded raw. They are not treated as a freeze.
 */
public class FrameDiagnostics extends CordovaPlugin {
    private static final String TAG = "FrameDiag";
    private static final long SAMPLE_PERIOD_MS = 3000;
    private static final long CALLBACK_TIMEOUT_MS = 1500;
    // Top-left of the existing page. Nothing is drawn there for this sample.
    private static final int MARKER_CSS_PX = 32;

    private static final String READ_JS =
        "(function(){var d=window.__cngFrameDiag||{};return JSON.stringify({" +
            "intervalSeq:d.intervalSeq||0,intervalTs:d.intervalTs||0," +
            "rafSeq:d.rafSeq||0,rafTs:d.rafTs||0," +
            "markerSeq:d.markerSeq||0,markerTs:d.markerTs||0," +
            "visibility:(document.visibilityState||'')" +
        "});})()";

    private final AtomicBoolean sampleInFlight = new AtomicBoolean(false);
    private HandlerThread workerThread;
    private Handler worker;
    private volatile boolean activityResumed = true;
    private volatile boolean started = false;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) {
        if (!"start".equals(action)) {
            return false;
        }
        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                startSampling();
            }
        });
        callbackContext.success();
        return true;
    }

    @Override
    public void onPause(boolean multitasking) {
        activityResumed = false;
    }

    @Override
    public void onResume(boolean multitasking) {
        activityResumed = true;
    }

    @Override
    public void onDestroy() {
        started = false;
        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
            worker = null;
        }
    }

    private void startSampling() {
        if (started) {
            return;
        }
        started = true;
        workerThread = new HandlerThread("FrameDiag");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        worker.post(sampleLoop);
    }

    private final Runnable sampleLoop = new Runnable() {
        @Override
        public void run() {
            if (!started || worker == null) {
                return;
            }
            if (sampleInFlight.compareAndSet(false, true)) {
                final boolean resumed = activityResumed;
                android.app.Activity activity = cordova.getActivity();
                if (activity == null) {
                    sampleInFlight.set(false);
                } else {
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                beginSample(resumed);
                            } catch (Throwable t) {
                                sampleInFlight.set(false);
                                Log.i(TAG, "sample failed " + t.getClass().getSimpleName());
                            }
                        }
                    });
                }
            }
            worker.postDelayed(this, SAMPLE_PERIOD_MS);
        }
    };

    private void beginSample(final boolean resumed) {
        final long sampleWall = System.currentTimeMillis();
        final JSONObject eval = new JSONObject();
        final JSONObject copy = new JSONObject();
        final AtomicBoolean evalDone = new AtomicBoolean(false);
        final AtomicBoolean copyDone = new AtomicBoolean(false);
        final AtomicBoolean reported = new AtomicBoolean(false);
        final long evalStart = SystemClock.elapsedRealtime();

        try {
            eval.put("timedOut", false);
            copy.put("timedOut", false);
            copy.put("skipped", false);
        } catch (JSONException ignored) {
        }

        View engineView = webView == null ? null : webView.getView();
        if (!(engineView instanceof WebView)) {
            finish(sampleWall, resumed, errorObject("eval", "no_webview"), errorObject("pixelCopy", "no_webview"));
            return;
        }
        final WebView page = (WebView) engineView;

        final Runnable evalTimeout = new Runnable() {
            @Override
            public void run() {
                if (evalDone.compareAndSet(false, true)) {
                    putLong(eval, "latencyMs", SystemClock.elapsedRealtime() - evalStart);
                    put(eval, "timedOut", true);
                    put(eval, "ok", false);
                    maybeFinish(sampleWall, resumed, eval, copy, evalDone, copyDone, reported);
                }
            }
        };
        if (worker != null) {
            worker.postDelayed(evalTimeout, CALLBACK_TIMEOUT_MS);
        }

        try {
        page.evaluateJavascript(READ_JS, new android.webkit.ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (!evalDone.compareAndSet(false, true)) {
                    return;
                }
                if (worker != null) {
                    worker.removeCallbacks(evalTimeout);
                }
                putLong(eval, "latencyMs", SystemClock.elapsedRealtime() - evalStart);
                put(eval, "ok", true);
                put(eval, "timedOut", false);
                if (value != null && value.length() >= 2 && value.charAt(0) == '"') {
                    value = value.substring(1, value.length() - 1).replace("\\\"", "\"");
                }
                try {
                    JSONObject body = new JSONObject(value == null ? "{}" : value);
                    eval.put("intervalSeq", body.optLong("intervalSeq"));
                    eval.put("intervalTs", body.optLong("intervalTs"));
                    eval.put("rafSeq", body.optLong("rafSeq"));
                    eval.put("rafTs", body.optLong("rafTs"));
                    eval.put("markerSeq", body.optLong("markerSeq"));
                    eval.put("markerTs", body.optLong("markerTs"));
                    eval.put("visibility", body.optString("visibility"));
                } catch (JSONException e) {
                    put(eval, "ok", false);
                    put(eval, "parseError", true);
                }
                maybeFinish(sampleWall, resumed, eval, copy, evalDone, copyDone, reported);
            }
        });
        } catch (Throwable t) {
            if (worker != null) {
                worker.removeCallbacks(evalTimeout);
            }
            if (evalDone.compareAndSet(false, true)) {
                put(eval, "ok", false);
                put(eval, "reason", "page");
                maybeFinish(sampleWall, resumed, eval, copy, evalDone, copyDone, reported);
            }
        }

        if (!resumed || Build.VERSION.SDK_INT < 26) {
            put(copy, "skipped", true);
            put(copy, "reason", !resumed ? "paused" : "api");
            copyDone.set(true);
            maybeFinish(sampleWall, resumed, eval, copy, evalDone, copyDone, reported);
            return;
        }

        final long copyStart = SystemClock.elapsedRealtime();
        Bitmap bitmap = null;
        Runnable copyTimeout = null;
        try {
        final Rect rect = markerRect(page);
        bitmap = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888);
        final Bitmap copied = bitmap;
        put(copy, "rectLeft", rect.left);
        put(copy, "rectTop", rect.top);
        put(copy, "width", rect.width());
        put(copy, "height", rect.height());

        copyTimeout = new Runnable() {
            @Override
            public void run() {
                if (copyDone.compareAndSet(false, true)) {
                    putLong(copy, "latencyMs", SystemClock.elapsedRealtime() - copyStart);
                    put(copy, "timedOut", true);
                    maybeFinish(sampleWall, resumed, eval, copy, evalDone, copyDone, reported);
                }
            }
        };
        final Runnable copyTimeoutTask = copyTimeout;
        if (worker != null) {
            worker.postDelayed(copyTimeoutTask, CALLBACK_TIMEOUT_MS);
        }

        Window window = cordova.getActivity().getWindow();
        PixelCopy.request(window, rect, copied, new PixelCopy.OnPixelCopyFinishedListener() {
            @Override
            public void onPixelCopyFinished(int resultCode) {
                if (worker == null) {
                    return;
                }
                worker.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!copyDone.compareAndSet(false, true)) {
                            if (!copied.isRecycled()) {
                                copied.recycle();
                            }
                            return;
                        }
                        worker.removeCallbacks(copyTimeoutTask);
                        putLong(copy, "latencyMs", SystemClock.elapsedRealtime() - copyStart);
                        put(copy, "code", resultCode);
                        put(copy, "codeName", codeName(resultCode));
                        if (resultCode == PixelCopy.SUCCESS && !copied.isRecycled()) {
                            int[] pixels = new int[copied.getWidth() * copied.getHeight()];
                            copied.getPixels(pixels, 0, copied.getWidth(), 0, 0, copied.getWidth(), copied.getHeight());
                            long checksum = 0;
                            boolean black = pixels.length > 0;
                            for (int pixel : pixels) {
                                checksum = (checksum * 1315423911L) ^ (pixel & 0xffffffffL);
                                int r = (pixel >> 16) & 0xff;
                                int g = (pixel >> 8) & 0xff;
                                int b = pixel & 0xff;
                                if (r > 8 || g > 8 || b > 8) {
                                    black = false;
                                }
                            }
                            putLong(copy, "checksum", checksum);
                            put(copy, "black", black);
                        }
                        copied.recycle();
                        maybeFinish(sampleWall, resumed, eval, copy, evalDone, copyDone, reported);
                    }
                });
            }
        }, worker);
        } catch (Throwable t) {
            if (worker != null && copyTimeout != null) {
                worker.removeCallbacks(copyTimeout);
            }
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
            if (copyDone.compareAndSet(false, true)) {
                put(copy, "ok", false);
                put(copy, "reason", "page");
                maybeFinish(sampleWall, resumed, eval, copy, evalDone, copyDone, reported);
            }
        }
    }

    private Rect markerRect(View page) {
        int[] loc = new int[2];
        page.getLocationOnScreen(loc);
        float density = page.getResources().getDisplayMetrics().density;
        int size = Math.max(1, Math.round(MARKER_CSS_PX * density));
        Rect rect = new Rect(loc[0], loc[1], loc[0] + size, loc[1] + size);
        View root = cordova.getActivity().getWindow().getDecorView();
        if (rect.right > root.getWidth()) {
            rect.right = root.getWidth();
        }
        if (rect.bottom > root.getHeight()) {
            rect.bottom = root.getHeight();
        }
        if (rect.width() < 1 || rect.height() < 1) {
            rect.set(0, 0, size, size);
        }
        return rect;
    }

    private void maybeFinish(long sampleWall, boolean resumed, JSONObject eval, JSONObject copy, AtomicBoolean evalDone, AtomicBoolean copyDone, AtomicBoolean reported) {
        if (evalDone.get() && copyDone.get() && reported.compareAndSet(false, true)) {
            finish(sampleWall, resumed, eval, copy);
        }
    }

    private void finish(long sampleWall, boolean resumed, JSONObject eval, JSONObject copy) {
        try {
            JSONObject line = new JSONObject();
            line.put("wallTs", sampleWall);
            line.put("activityResumed", resumed);
            line.put("eval", eval);
            line.put("pixelCopy", copy);
            Log.i(TAG, line.toString());
        } catch (JSONException ignored) {
        }
        sampleInFlight.set(false);
    }

    private static JSONObject errorObject(String unused, String reason) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("skipped", true);
            o.put("reason", reason);
        } catch (JSONException ignored) {
        }
        return o;
    }

    private static void put(JSONObject o, String key, Object value) {
        try {
            o.put(key, value);
        } catch (JSONException ignored) {
        }
    }

    private static void putLong(JSONObject o, String key, long value) {
        try {
            o.put(key, value);
        } catch (JSONException ignored) {
        }
    }

    private static String codeName(int code) {
        if (Build.VERSION.SDK_INT < 26) {
            return "UNAVAILABLE";
        }
        switch (code) {
            case PixelCopy.SUCCESS:
                return "SUCCESS";
            case PixelCopy.ERROR_UNKNOWN:
                return "ERROR_UNKNOWN";
            case PixelCopy.ERROR_TIMEOUT:
                return "ERROR_TIMEOUT";
            case PixelCopy.ERROR_SOURCE_NO_DATA:
                return "ERROR_SOURCE_NO_DATA";
            case PixelCopy.ERROR_SOURCE_INVALID:
                return "ERROR_SOURCE_INVALID";
            case PixelCopy.ERROR_DESTINATION_INVALID:
                return "ERROR_DESTINATION_INVALID";
            default:
                return "CODE_" + code;
        }
    }
}
