package com.joshua.sp1merge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Runs the stem merge as a foreground service (with a visible,
 * ongoing notification) instead of a plain background thread tied to
 * the Activity. A plain background thread gets killed as soon as
 * Android decides to reclaim the app's process — which happens
 * readily once the app is backgrounded, especially on Samsung's
 * aggressive battery management. A foreground service tells the OS
 * this is active, user-visible work, which makes it dramatically less
 * likely to be killed while a merge is running.
 */
public class MergeService extends Service {

    public static final String COMMAND_CANCEL = "com.joshua.sp1merge.COMMAND_CANCEL";

    public static final String ACTION_PROGRESS = "com.joshua.sp1merge.PROGRESS";
    public static final String ACTION_DONE = "com.joshua.sp1merge.DONE";
    public static final String ACTION_ERROR = "com.joshua.sp1merge.ERROR";
    public static final String ACTION_CANCELLED = "com.joshua.sp1merge.CANCELLED";
    public static final String EXTRA_STAGE = "stage";
    public static final String EXTRA_PERCENT = "percent";
    public static final String EXTRA_MESSAGE = "message";

    public static final String EXTRA_VOCALS = "vocals";
    public static final String EXTRA_OTHER = "other";
    public static final String EXTRA_BASS = "bass";
    public static final String EXTRA_DRUMS = "drums";
    public static final String EXTRA_OUTPUT = "output";

    private static final String CHANNEL_ID = "merge_progress";
    private static final int NOTIFICATION_ID = 1;

    // How long to wait for a cooperative interrupt to take effect
    // before giving up on the thread and forcing the service to stop
    // anyway. Covers the case where the thread is stuck in a blocking
    // native call (e.g. MediaExtractor.setDataSource on a misbehaving
    // source) that never checks the interrupted flag at all.
    private static final long CANCEL_GRACE_MS = 5000;

    private volatile Thread mergeThread;
    private volatile Uri currentOutput;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable cancelTimeout;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        DebugLog.log(this, "Service", "onStartCommand action=" + (intent != null ? intent.getAction() : "null"));
        if (intent != null && COMMAND_CANCEL.equals(intent.getAction())) {
            requestCancel();
            return START_NOT_STICKY;
        }

        final Uri vocals = intent.getParcelableExtra(EXTRA_VOCALS);
        final Uri other = intent.getParcelableExtra(EXTRA_OTHER);
        final Uri bass = intent.getParcelableExtra(EXTRA_BASS);
        final Uri drums = intent.getParcelableExtra(EXTRA_DRUMS);
        final Uri output = intent.getParcelableExtra(EXTRA_OUTPUT);

        DebugLog.log(this, "Service", "calling startForeground");
        startForeground(NOTIFICATION_ID, buildNotification("Starting…", 0));
        DebugLog.log(this, "Service", "startForeground returned, launching merge thread");
        runMerge(vocals, other, bass, drums, output);
        return START_NOT_STICKY;
    }

    private void requestCancel() {
        Thread t = mergeThread;
        if (t == null) {
            DebugLog.log(this, "Service", "requestCancel: no thread running");
            return;
        }
        DebugLog.log(this, "Service", "requestCancel: interrupting thread, arming " + CANCEL_GRACE_MS + "ms grace timer");
        t.interrupt();
        cancelTimeout = new Runnable() {
            @Override
            public void run() {
                // the thread never responded to interrupt() within the
                // grace period — most likely stuck in a blocking call
                // that ignores it. Stop waiting on it: clean up what we
                // can and let the service (and its notification) go
                // regardless. The thread itself is daemon, so it can't
                // keep the app process alive on its own if it really
                // never returns.
                if (mergeThread != null) {
                    DebugLog.log(MergeService.this, "Service", "cancel grace period elapsed, forcing stop");
                    deleteOutputQuietly(currentOutput);
                    broadcastCancelled();
                    mergeThread = null;
                    stopForeground(true);
                    stopSelf();
                }
            }
        };
        handler.postDelayed(cancelTimeout, CANCEL_GRACE_MS);
    }

    private void runMerge(final Uri vocals, final Uri other, final Uri bass,
                           final Uri drums, final Uri output) {
        currentOutput = output;
        mergeThread = new Thread(new Runnable() {
            @Override
            public void run() {
                DebugLog.log(MergeService.this, "MergeThread", "thread started");
                try {
                    StemMerger.merge(MergeService.this, vocals, other, bass, drums, output,
                            new StemMerger.ProgressListener() {
                                @Override
                                public void onProgress(String stage, int percent) {
                                    DebugLog.log(MergeService.this, "MergeThread",
                                            "onProgress stage=\"" + stage + "\" percent=" + percent);
                                    updateNotification(stage, percent);
                                    broadcastProgress(stage, percent);
                                }
                            });
                    DebugLog.log(MergeService.this, "MergeThread", "merge() returned normally");
                    broadcastDone();
                } catch (Throwable t) {
                    DebugLog.log(MergeService.this, "MergeThread",
                            "caught " + t.getClass().getSimpleName() + ": " + t.getMessage()
                                    + " (interrupted=" + Thread.currentThread().isInterrupted() + ")");
                    deleteOutputQuietly(output);
                    if (Thread.currentThread().isInterrupted() || t instanceof MergeCancelledException) {
                        broadcastCancelled();
                    } else {
                        String message = t.getMessage();
                        broadcastError(message != null ? message : t.getClass().getSimpleName());
                    }
                } finally {
                    DebugLog.log(MergeService.this, "MergeThread", "finally block running, stopping service");
                    if (cancelTimeout != null) {
                        handler.removeCallbacks(cancelTimeout);
                        cancelTimeout = null;
                    }
                    mergeThread = null;
                    stopForeground(true);
                    stopSelf();
                }
            }
        });
        mergeThread.setDaemon(true);
        DebugLog.log(this, "Service", "starting merge thread now");
        mergeThread.start();
    }

    private void deleteOutputQuietly(Uri output) {
        if (output == null) {
            return;
        }
        try {
            getContentResolver().delete(output, null, null);
        } catch (Exception ignored) {
        }
    }

    private Notification buildNotification(String text, int percent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Merge progress", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setContentTitle("SP1 Merge")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(100, percent, false)
                .setOngoing(true);
        return builder.build();
    }

    private void updateNotification(String text, int percent) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildNotification(text, percent));
    }

    private void broadcastProgress(String stage, int percent) {
        Intent i = new Intent(ACTION_PROGRESS);
        i.putExtra(EXTRA_STAGE, stage);
        i.putExtra(EXTRA_PERCENT, percent);
        DebugLog.log(this, "Service", "sendBroadcast PROGRESS");
        sendBroadcast(i);
    }

    private void broadcastDone() {
        DebugLog.log(this, "Service", "sendBroadcast DONE");
        sendBroadcast(new Intent(ACTION_DONE));
    }

    private void broadcastError(String message) {
        Intent i = new Intent(ACTION_ERROR);
        i.putExtra(EXTRA_MESSAGE, message);
        DebugLog.log(this, "Service", "sendBroadcast ERROR: " + message);
        sendBroadcast(i);
    }

    private void broadcastCancelled() {
        DebugLog.log(this, "Service", "sendBroadcast CANCELLED");
        sendBroadcast(new Intent(ACTION_CANCELLED));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
