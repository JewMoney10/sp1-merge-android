package com.joshua.sp1merge;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Temporary diagnostic logger. Writes timestamped lines to a plain
 * text file in the app's own external files folder (no permissions
 * needed) so a stuck or failed run can be inspected afterward. Use
 * the in-app "Show Log" button to view/copy it — no file manager
 * access needed.
 */
public class DebugLog {

    private static final Object LOCK = new Object();
    private static final SimpleDateFormat FORMAT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    public static void log(Context context, String tag, String message) {
        synchronized (LOCK) {
            try {
                File dir = context.getApplicationContext().getExternalFilesDir(null);
                if (dir == null) {
                    return;
                }
                File logFile = new File(dir, "merge_debug.log");
                FileWriter writer = new FileWriter(logFile, true);
                try {
                    String time = FORMAT.format(new Date());
                    writer.write(time + " [" + tag + "] " + message + "\n");
                } finally {
                    writer.close();
                }
            } catch (IOException ignored) {
            }
        }
    }

    public static String readAll(Context context) {
        try {
            File dir = context.getApplicationContext().getExternalFilesDir(null);
            if (dir == null) {
                return "(no external files dir)";
            }
            File logFile = new File(dir, "merge_debug.log");
            if (!logFile.exists()) {
                return "(no log yet — run a merge first)";
            }
            java.io.FileInputStream in = new java.io.FileInputStream(logFile);
            try {
                byte[] buf = new byte[(int) logFile.length()];
                in.read(buf);
                return new String(buf, "UTF-8");
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return "(error reading log: " + e.getMessage() + ")";
        }
    }

    public static void clear(Context context) {
        try {
            File dir = context.getApplicationContext().getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            File logFile = new File(dir, "merge_debug.log");
            if (logFile.exists()) {
                logFile.delete();
            }
        } catch (Exception ignored) {
        }
    }
}
