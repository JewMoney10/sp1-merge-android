package com.joshua.sp1merge;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_VOCALS = 1;
    private static final int REQ_OTHER = 2;
    private static final int REQ_BASS = 3;
    private static final int REQ_DRUMS = 4;
    private static final int REQ_OUTPUT = 5;

    private Uri vocalsUri, otherUri, bassUri, drumsUri;

    private TextView vocalsLabel, otherLabel, bassLabel, drumsLabel, statusText;
    private Button mergeButton;
    private Button cancelButton;
    private final java.util.List<Button> pickButtons = new java.util.ArrayList<>();
    private ProgressBar progressBar;
    private EditText outputName;

    // Listens for progress/done/error broadcasts from MergeService,
    // which does the actual work so it survives the app being backgrounded.
    private final BroadcastReceiver mergeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            DebugLog.log(MainActivity.this, "Activity", "onReceive action=" + action);
            if (MergeService.ACTION_PROGRESS.equals(action)) {
                int percent = intent.getIntExtra(MergeService.EXTRA_PERCENT, 0);
                String stage = intent.getStringExtra(MergeService.EXTRA_STAGE);
                if (progressBar.isIndeterminate()) {
                    progressBar.setIndeterminate(false);
                }
                progressBar.setProgress(percent);
                statusText.setText(stage);
            } else if (MergeService.ACTION_DONE.equals(action)) {
                progressBar.setIndeterminate(false);
                progressBar.setProgress(100);
                statusText.setText("Done");
                Toast.makeText(MainActivity.this, "merge complete", Toast.LENGTH_LONG).show();
                mergeButton.setEnabled(true);
                cancelButton.setEnabled(false);
                outputName.setEnabled(true);
                setPickersEnabled(true);
            } else if (MergeService.ACTION_ERROR.equals(action)) {
                String message = intent.getStringExtra(MergeService.EXTRA_MESSAGE);
                progressBar.setIndeterminate(false);
                statusText.setText("Failed: " + message);
                Toast.makeText(MainActivity.this, "merge failed", Toast.LENGTH_LONG).show();
                mergeButton.setEnabled(true);
                cancelButton.setEnabled(false);
                outputName.setEnabled(true);
                setPickersEnabled(true);
            } else if (MergeService.ACTION_CANCELLED.equals(action)) {
                progressBar.setIndeterminate(false);
                progressBar.setProgress(0);
                statusText.setText("Cancelled");
                mergeButton.setEnabled(true);
                cancelButton.setEnabled(false);
                outputName.setEnabled(true);
                setPickersEnabled(true);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());
    }

    @Override
    protected void onResume() {
        super.onResume();
        DebugLog.log(this, "Activity", "onResume: registering receiver");
        IntentFilter filter = new IntentFilter();
        filter.addAction(MergeService.ACTION_PROGRESS);
        filter.addAction(MergeService.ACTION_DONE);
        filter.addAction(MergeService.ACTION_ERROR);
        filter.addAction(MergeService.ACTION_CANCELLED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mergeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mergeReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        DebugLog.log(this, "Activity", "onPause: unregistering receiver");
        unregisterReceiver(mergeReceiver);
    }

    // Built in code rather than XML so the same layout works in both
    // orientations without a layout-land variant: it's a ScrollView,
    // so landscape just means more scrolling, never clipped content.
    private View buildLayout() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        vocalsLabel = addPicker(root, "Vocals", REQ_VOCALS);
        otherLabel = addPicker(root, "Other", REQ_OTHER);
        bassLabel = addPicker(root, "Bass", REQ_BASS);
        drumsLabel = addPicker(root, "Drums", REQ_DRUMS);

        TextView nameLabel = new TextView(this);
        nameLabel.setText("Output file name");
        nameLabel.setPadding(0, dp(16), 0, dp(4));
        root.addView(nameLabel);

        outputName = new EditText(this);
        outputName.setText("merged");
        root.addView(outputName);

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER_HORIZONTAL);

        mergeButton = new Button(this);
        mergeButton.setText("Merge");
        mergeButton.setEnabled(false);
        mergeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startOutputPicker();
            }
        });
        buttonRow.addView(mergeButton);

        cancelButton = new Button(this);
        cancelButton.setText("Cancel");
        cancelButton.setEnabled(false);
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelMerge();
            }
        });
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cancelParams.leftMargin = dp(12);
        buttonRow.addView(cancelButton, cancelParams);

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(24);
        rowParams.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(buttonRow, rowParams);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        progressParams.topMargin = dp(16);
        root.addView(progressBar, progressParams);

        statusText = new TextView(this);
        statusText.setPadding(0, dp(8), 0, 0);
        root.addView(statusText);

        Button logButton = new Button(this);
        logButton.setText("Show Log");
        logButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLog();
            }
        });
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        logParams.topMargin = dp(16);
        root.addView(logButton, logParams);

        scroll.addView(root);
        return scroll;
    }

    // Shows the diagnostic log in a selectable text dialog — long-press
    // to select all and copy, then paste it wherever it needs to go.
    // No file manager or special storage access required.
    private void showLog() {
        final String content = DebugLog.readAll(this);
        final TextView logView = new TextView(this);
        logView.setText(content);
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(16), dp(16), dp(16), dp(16));
        ScrollView logScroll = new ScrollView(this);
        logScroll.addView(logView);

        new android.app.AlertDialog.Builder(this)
                .setTitle("Merge debug log")
                .setView(logScroll)
                .setPositiveButton("Copy", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("merge log", content));
                        Toast.makeText(MainActivity.this, "Log copied", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("Close", null)
                .setNegativeButton("Clear log", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        DebugLog.clear(MainActivity.this);
                    }
                })
                .show();
    }

    private TextView addPicker(LinearLayout root, final String label, final int requestCode) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        Button pick = new Button(this);
        pick.setText("Pick " + label);
        pick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("audio/*");
                startActivityForResult(intent, requestCode);
            }
        });
        row.addView(pick);
        pickButtons.add(pick);

        TextView status = new TextView(this);
        status.setText("not selected");
        status.setPadding(dp(12), 0, 0, 0);
        row.addView(status);

        root.addView(row);
        return status;
    }

    private void startOutputPicker() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/wav");
        String name = outputName.getText().toString().trim();
        if (name.isEmpty()) name = "merged";
        if (!name.endsWith(".wav")) name = name + ".wav";
        intent.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(intent, REQ_OUTPUT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        if (requestCode == REQ_OUTPUT) {
            runMerge(uri);
            return;
        }

        switch (requestCode) {
            case REQ_VOCALS:
                vocalsUri = uri;
                vocalsLabel.setText(displayName(uri));
                break;
            case REQ_OTHER:
                otherUri = uri;
                otherLabel.setText(displayName(uri));
                break;
            case REQ_BASS:
                bassUri = uri;
                bassLabel.setText(displayName(uri));
                break;
            case REQ_DRUMS:
                drumsUri = uri;
                drumsLabel.setText(displayName(uri));
                break;
            default:
                return;
        }
        mergeButton.setEnabled(vocalsUri != null && otherUri != null
                && bassUri != null && drumsUri != null);
    }

    // Hands the job off to MergeService instead of running it here, so
    // it keeps going (with a visible notification) even if the app is
    // backgrounded or the screen locks.
    private void runMerge(Uri outputUri) {
        DebugLog.log(this, "Activity", "runMerge: starting service, output=" + outputUri);
        mergeButton.setEnabled(false);
        cancelButton.setEnabled(true);
        outputName.setEnabled(false);
        setPickersEnabled(false);
        statusText.setText("Starting…");
        progressBar.setIndeterminate(true);

        Intent serviceIntent = new Intent(this, MergeService.class);
        serviceIntent.putExtra(MergeService.EXTRA_VOCALS, vocalsUri);
        serviceIntent.putExtra(MergeService.EXTRA_OTHER, otherUri);
        serviceIntent.putExtra(MergeService.EXTRA_BASS, bassUri);
        serviceIntent.putExtra(MergeService.EXTRA_DRUMS, drumsUri);
        serviceIntent.putExtra(MergeService.EXTRA_OUTPUT, outputUri);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    // Tells MergeService to interrupt the in-progress merge. The
    // service deletes the partial output file and broadcasts
    // ACTION_CANCELLED once it's actually stopped.
    private void cancelMerge() {
        DebugLog.log(this, "Activity", "cancelMerge: sending cancel command");
        cancelButton.setEnabled(false);
        statusText.setText("Cancelling…");
        Intent cancelIntent = new Intent(this, MergeService.class);
        cancelIntent.setAction(MergeService.COMMAND_CANCEL);
        startService(cancelIntent);
    }

    private void setPickersEnabled(boolean enabled) {
        for (Button b : pickButtons) {
            b.setEnabled(enabled);
        }
    }

    private String displayName(Uri uri) {
        String result = uri.getLastPathSegment();
        Cursor cursor = getContentResolver().query(uri, null, null, null, null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) result = cursor.getString(idx);
                }
            } finally {
                cursor.close();
            }
        }
        return result;
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
