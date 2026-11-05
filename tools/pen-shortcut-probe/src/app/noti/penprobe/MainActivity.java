package app.noti.penprobe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    static final String[] MODES = {
        "1. Normal window", "2. Screenshot protection only",
        "3. Xiaomi note window identity only", "4. Both experiments"
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(24, 24, 24, 24);
        TextView title = new TextView(this);
        title.setText("Noti Pen Probe");
        title.setTextSize(24);
        content.addView(title);
        TextView help = new TextView(this);
        help.setText("Test the writing and screenshot buttons in each mode. Hold a button for two seconds, "
            + "then keep it held while drawing. Watch for Mi Canvas, screenshot UI, messages, and missing ink.\n\n"
            + "Each mode opens a fresh test window. Record each button separately, return here, then test the next mode. "
            + "A successful mode needs no system action or warning, continued ink input, and usable button events.\n\n"
            + "These are unverified experiments for Pad 7 / HyperOS 3.0.303.0. They change only this test window. "
            + "Screenshot protection prevents system screen capture of that window. "
            + "The real app ID remains app.noti.penprobe; Noti is untouched. No permissions are requested.\n");
        help.setTextSize(16);
        content.addView(help);
        Button inspect = new Button(this);
        inspect.setText("Inspect firmware (copy text)");
        inspect.setOnClickListener(v -> startActivity(new Intent(this, FirmwareTextActivity.class)));
        content.addView(inspect);
        for (int i = 0; i < MODES.length; i++) {
            final int mode = i;
            Button button = new Button(this);
            button.setText(MODES[i]);
            button.setOnClickListener(v -> startActivity(new Intent(this, ProbeActivity.class).putExtra("mode", mode)));
            content.addView(button);
        }
        Button export = new Button(this);
        export.setText("Export pen firmware");
        export.setOnClickListener(v -> startActivity(new Intent(this, FirmwareExportActivity.class)));
        content.addView(export);
        Button lastReport = new Button(this);
        lastReport.setText("Copy last test / failure report");
        lastReport.setOnClickListener(v -> {
            String report = getSharedPreferences("probe", MODE_PRIVATE).getString("report", "No test report saved yet.");
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("Pen probe", report));
            android.widget.Toast.makeText(this, "Last report copied", android.widget.Toast.LENGTH_SHORT).show();
        });
        content.addView(lastReport);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setContentView(scroll);
    }
}
