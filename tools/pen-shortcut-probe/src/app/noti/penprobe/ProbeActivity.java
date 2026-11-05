package app.noti.penprobe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayDeque;
import java.util.Locale;

public final class ProbeActivity extends Activity {
    private static final String XIAOMI_NOTE_WINDOW = "com.miui.creation";
    private final ArrayDeque<String> events = new ArrayDeque<>();
    private TextView log;
    private String header;
    private long started;
    private int mode;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        started = SystemClock.uptimeMillis();
        mode = getIntent().getIntExtra("mode", 0);
        if (mode < 0 || mode >= MainActivity.MODES.length) mode = 0;

        // Set identity BEFORE attaching the decor. Android's LayoutParams.copyFrom
        // only copies packageName if the destination has none: a later update
        // cannot establish or undo this experiment on an already added window.
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        if ((mode & 2) != 0) attrs.packageName = XIAOMI_NOTE_WINDOW;
        if ((mode & 1) != 0) attrs.flags |= WindowManager.LayoutParams.FLAG_SECURE;
        getWindow().setAttributes(attrs);

        header = "Noti Pen Probe v3\n" + MainActivity.MODES[mode]
            + "\nDevice: " + Build.MANUFACTURER + " " + Build.MODEL
            + "\nAndroid: " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT
            + "\nBuild: " + Build.DISPLAY + "\nFingerprint: " + Build.FINGERPRINT
            + "\nInstalled package: " + getPackageName()
            + "\nRequested window package: " + getWindow().getAttributes().packageName
            + "\nSecure flag: " + ((getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0)
            + "\nWindow attributes are requests, not evidence that HyperOS accepted the exemption.\n";
        saveReport();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left + 16, bars.top + 8, bars.right + 16, bars.bottom + 8);
            return insets;
        });
        TextView title = new TextView(this);
        title.setText(MainActivity.MODES[mode] + "\n" + Build.MODEL + " · " + Build.DISPLAY
            + "\nHold each button, then draw while holding. Observe any system UI.");
        title.setTextSize(16);
        root.addView(title);
        InkView ink = new InkView();
        root.addView(ink, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout actions = new LinearLayout(this);
        Button back = new Button(this);
        back.setText("Modes");
        back.setOnClickListener(v -> finish());
        actions.addView(back, new LinearLayout.LayoutParams(0, -2, 1));
        Button clear = new Button(this);
        clear.setText("Clear ink");
        clear.setOnClickListener(v -> ink.clear());
        actions.addView(clear, new LinearLayout.LayoutParams(0, -2, 1));
        Button copy = new Button(this);
        copy.setText("Copy report");
        copy.setOnClickListener(v -> {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Pen probe", report()));
            Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show();
        });
        actions.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(actions);
        log = new TextView(this);
        log.setTextSize(12);
        log.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(log);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 0.65f));
        setContentView(root);
        record("Test window created; event time uses uptime.");
    }

    private void record(String line) {
        events.addLast((SystemClock.uptimeMillis() - started) + " ms: " + line);
        while (events.size() > 200) events.removeFirst();
        if (log != null) {
            StringBuilder text = new StringBuilder();
            java.util.Iterator<String> it = events.descendingIterator();
            while (it.hasNext()) text.append(it.next()).append('\n');
            log.setText(text.toString());
        }
    }

    private String report() {
        StringBuilder text = new StringBuilder(header).append('\n');
        for (String line : events) text.append(line).append('\n');
        return text.toString();
    }

    private void saveReport() {
        getSharedPreferences("probe", MODE_PRIVATE).edit().putString("report", report()).commit();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        InputDevice device = event.getDevice();
        String name = device == null ? "unknown" : device.getName();
        int code = event.getKeyCode();
        boolean penCode = code == 308 || code == 309 || code == 310;
        boolean pageCode = code == KeyEvent.KEYCODE_PAGE_UP || code == KeyEvent.KEYCODE_PAGE_DOWN;
        String lower = name.toLowerCase(Locale.ROOT);
        boolean penDevice = event.isFromSource(InputDevice.SOURCE_STYLUS)
            || ((lower.contains("pen") || lower.contains("stylus")) && !lower.contains("keyboard"));
        if (event.getRepeatCount() == 0 || event.getRepeatCount() == 1) {
            String action = event.getAction() == KeyEvent.ACTION_DOWN ? "DOWN"
                : event.getAction() == KeyEvent.ACTION_UP ? "UP" : "MULTIPLE";
            record("KEY " + action + " " + code
                + " (" + KeyEvent.keyCodeToString(code) + ") device=" + event.getDeviceId()
                + " name=" + name + " source=0x" + Integer.toHexString(event.getSource())
                + " keyboardType=" + (device == null ? -1 : device.getKeyboardType())
                + " fullKeyboard=" + (device != null && device.supportsSource(InputDevice.SOURCE_KEYBOARD)
                    && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC)
                + " held=" + (event.getEventTime() - event.getDownTime()) + "ms"
                + " repeat=" + event.getRepeatCount() + " canceled=" + event.isCanceled());
        }
        if (penCode || (pageCode && penDevice)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        record("WINDOW FOCUS " + focused);
        if (!focused) saveReport();
    }

    @Override protected void onPause() {
        record("ACTIVITY PAUSED");
        saveReport();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        record("ACTIVITY RESUMED");
    }

    private final class InkView extends View {
        private final Path ink = new Path();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int lastButtons = -1;
        private boolean drawing;
        InkView() {
            super(ProbeActivity.this);
            setBackgroundColor(0xffedf4ff);
            setContentDescription("Pen test drawing area");
            paint.setColor(0xff184790);
            paint.setStrokeWidth(4);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            label.setColor(0xff445577);
            label.setTextSize(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, 16, getResources().getDisplayMetrics()));
        }
        void clear() { ink.reset(); drawing = false; invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawText("Draw here with the pen", 16, label.getTextSize() + 12, label);
            canvas.drawPath(ink, paint);
        }
        private void observe(MotionEvent event) {
            int bits = event.getButtonState();
            if (bits != lastButtons) {
                lastButtons = bits;
                record("MOTION buttons=0x" + Integer.toHexString(bits) + " action="
                    + MotionEvent.actionToString(event.getAction()) + " device=" + event.getDeviceId()
                    + " tool=" + event.getToolType(0));
            }
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            observe(event);
            int tool = event.getToolType(0);
            if (tool != MotionEvent.TOOL_TYPE_STYLUS && tool != MotionEvent.TOOL_TYPE_ERASER) return true;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                record("NIB DOWN buttons=0x" + Integer.toHexString(event.getButtonState()));
                ink.moveTo(event.getX(), event.getY());
                drawing = true;
            } else if (action == MotionEvent.ACTION_MOVE && drawing) {
                for (int i = 0; i < event.getHistorySize(); i++) ink.lineTo(event.getHistoricalX(i), event.getHistoricalY(i));
                ink.lineTo(event.getX(), event.getY());
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (drawing && action == MotionEvent.ACTION_UP) ink.lineTo(event.getX(), event.getY());
                drawing = false;
                record(action == MotionEvent.ACTION_UP ? "NIB UP" : "NIB CANCELED");
            }
            invalidate();
            return true;
        }
        @Override public boolean onHoverEvent(MotionEvent event) {
            observe(event);
            return true;
        }
        @Override public boolean onGenericMotionEvent(MotionEvent event) {
            observe(event);
            return true;
        }
    }
}
