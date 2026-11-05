package app.noti.penprobe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.*;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.*;

/** Local text inspection for clients where a firmware ZIP cannot be attached. */
public final class FirmwareTextActivity extends Activity {
    private final LinkedHashMap<String, String> reports = new LinkedHashMap<>();
    private final ArrayList<String> names = new ArrayList<>();
    private Spinner section;
    private TextView status;
    private TextView text;
    private Button copy;
    private Button previous;
    private Button next;
    private int part;
    private String visible = "";
    private static final int PART_SIZE = 6500;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left + 16, bars.top + 8, bars.right + 16, bars.bottom + 8);
            return insets;
        });
        status = new TextView(this);
        status.setText("Reading pen-policy code locally… No code is executed or changed, and no files are uploaded. "
            + "After inspection, copy Summary and Screenshot handler into the chat. No hold tests are needed.");
        root.addView(status);
        section = new Spinner(this);
        section.setEnabled(false);
        root.addView(section);
        LinearLayout actions = new LinearLayout(this);
        previous = new Button(this);
        previous.setText("Previous");
        previous.setOnClickListener(v -> { part--; showPart(); });
        actions.addView(previous, new LinearLayout.LayoutParams(0, -2, 1));
        copy = new Button(this);
        copy.setText("Copy this part");
        copy.setEnabled(false);
        copy.setOnClickListener(v -> {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Pen firmware text", visible));
            Toast.makeText(this, "Report part copied", Toast.LENGTH_SHORT).show();
        });
        actions.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        next = new Button(this);
        next.setText("Next");
        next.setOnClickListener(v -> { part++; showPart(); });
        actions.addView(next, new LinearLayout.LayoutParams(0, -2, 1));
        previous.setEnabled(false);
        next.setEnabled(false);
        root.addView(actions);
        text = new TextView(this);
        text.setTextSize(12);
        text.setTextIsSelectable(true);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Button back = new Button(this);
        back.setText("Back to probe");
        back.setOnClickListener(v -> finish());
        root.addView(back);
        setContentView(root);
        new Thread(this::inspect, "pen-policy-text").start();
    }

    private void inspect() {
        TargetedDexReport inspector = new TargetedDexReport();
        inspector.note("Fixed allowlist: miui-services.jar / miui-framework.jar only. Parser: smali-dexlib2 3.0.10.");
        Set<String> seen = new HashSet<>();
        for (String base : new String[]{"/system_ext/framework/", "/system/framework/", "/product/framework/"}) {
            for (String name : new String[]{"miui-services.jar", "miui-framework.jar"}) {
                File file = new File(base, name);
                try {
                    if (!seen.add(file.getCanonicalPath())) continue;
                    if (!file.isFile()) { inspector.note("NOT FOUND/NOT VISIBLE " + file); continue; }
                    if (file.length() > 96L * 1024 * 1024) { inspector.note("SKIP size limit " + file); continue; }
                    inspector.note("FILE " + file + " bytes=" + file.length() + " sha256=" + sha256(file));
                    inspector.inspect(file);
                } catch (Exception error) {
                    inspector.note("READ/PARSE FAILED " + file + ": " + error);
                }
            }
        }
        final LinkedHashMap<String, String> result = inspector.finish();
        runOnUiThread(() -> {
            reports.putAll(result);
            names.addAll(result.keySet());
            section.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
            section.setEnabled(true);
            section.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { part = 0; showPart(); }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
            showPart();
        });
    }

    private void showPart() {
        if (names.isEmpty()) return;
        String name = names.get(Math.max(0, section.getSelectedItemPosition()));
        String report = reports.get(name);
        int parts = Math.max(1, (report.length() + PART_SIZE - 1) / PART_SIZE);
        part = Math.max(0, Math.min(part, parts - 1));
        int start = part * PART_SIZE;
        visible = "Noti pen firmware text v3\n" + Build.FINGERPRINT
            + "\nSection: " + name + " · Part " + (part + 1) + "/" + parts
            + "\nRaw DEX instructions; addresses are code units, not Java source lines.\n\n"
            + report.substring(start, Math.min(start + PART_SIZE, report.length()));
        text.setText(visible);
        status.setText("Inspection finished. Copy Summary, then Screenshot handler. "
            + "Use Next if a section has several parts. Read/parse failures appear in Summary.");
        copy.setEnabled(true);
        previous.setEnabled(part > 0);
        next.setEnabled(part + 1 < parts);
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream stream = new FileInputStream(file)) {
            byte[] bytes = new byte[65536];
            int size;
            while ((size = stream.read(bytes)) != -1) digest.update(bytes, 0, size);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
