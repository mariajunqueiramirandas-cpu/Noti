package app.noti.penprobe;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Copies a fixed set of readable system code files after a user-selected save. */
public final class FirmwareExportActivity extends Activity {
    private static final int SAVE_ZIP = 31;
    // Keeping raw payload below 24 MiB also bounds the ZIP below chat's 32 MiB
    // file limit, including DEFLATE overhead and a small metadata report.
    private static final int MAX_PAYLOAD = 24 * 1024 * 1024;
    private Button save;
    private TextView status;
    private boolean busy;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(24, 24, 24, 24);
        TextView title = new TextView(this);
        title.setText("Export pen firmware");
        title.setTextSize(24);
        content.addView(title);
        TextView help = new TextView(this);
        help.setText("This saves a ZIP for inspecting the screenshot-button handler on your exact HyperOS build.\n\n"
            + "It includes readable Xiaomi framework JARs, code/manifest entries from Screenshot and Security Core, "
            + "and a report of the build, file versions, hashes and read failures. "
            + "Only this fixed list of system code is read. Notes, accounts, personal files and app settings are not collected.\n\n"
            + "No root, ADB or permission grant is needed. Some firmware may deny reading a file; "
            + "the report will record that. Nothing is uploaded automatically.\n\n"
            + "Keep the pen connected. Choose a save location, wait for completion, then attach the saved ZIP in the chat. "
            + "This can take a few seconds.\n");
        help.setTextSize(16);
        content.addView(help);
        save = new Button(this);
        save.setText("Save firmware ZIP");
        save.setOnClickListener(v -> {
            if (busy) return;
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, "noti-pen-firmware.zip");
            startActivityForResult(intent, SAVE_ZIP);
        });
        content.addView(save);
        status = new TextView(this);
        status.setText("Ready. Export does not change pen behavior or global settings.");
        content.addView(status);
        Button back = new Button(this);
        back.setText("Back to probe");
        back.setOnClickListener(v -> { if (!busy) finish(); });
        content.addView(back);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setContentView(scroll);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != SAVE_ZIP || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri destination = data.getData();
        busy = true;
        save.setEnabled(false);
        status.setText("Reading the fixed system-code list and saving ZIP…");
        new Thread(() -> {
            String outcome;
            try (OutputStream target = getContentResolver().openOutputStream(destination, "wt")) {
                if (target == null) throw new IOException("The selected location returned no output stream");
                try (ZipOutputStream archive = new ZipOutputStream(target)) {
                    Collector collector = new Collector(archive);
                    collector.collect();
                    outcome = "Saved firmware ZIP. " + collector.files + " code entries, "
                        + (collector.bytes / 1024) + " KiB before compression.\n"
                        + "Attach noti-pen-firmware.zip in the chat. The report inside lists any denied or skipped files.";
                }
            } catch (Exception error) {
                outcome = "Export failed: " + error + "\nThe selected file may be incomplete. Retry with another local location.";
            }
            final String message = outcome;
            runOnUiThread(() -> {
                busy = false;
                save.setEnabled(true);
                status.setText(message);
            });
        }, "pen-firmware-export").start();
    }

    private final class Collector {
        final ZipOutputStream archive;
        final StringBuilder report = new StringBuilder();
        final Set<String> seen = new HashSet<>();
        int bytes;
        int files;
        Collector(ZipOutputStream archive) { this.archive = archive; }

        void collect() throws Exception {
            report.append("Noti pen firmware export v2\nFingerprint: ").append(Build.FINGERPRINT)
                .append("\nModel: ").append(Build.MODEL).append("\nAPI: ").append(Build.VERSION.SDK_INT)
                .append("\nMaximum raw code payload: ").append(MAX_PAYLOAD).append(" bytes\n")
                .append("Fixed system-code allowlist only; no user data or settings.\n\n");
            report.append("Pen input devices (fullKeyboard is computed using Android's public source/type fields):\n");
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice device = InputDevice.getDevice(id);
                if (device == null) continue;
                String name = device.getName().toLowerCase(Locale.ROOT);
                if (!device.supportsSource(InputDevice.SOURCE_STYLUS) && !name.contains("pen") && !name.contains("stylus")) continue;
                boolean full = device.supportsSource(InputDevice.SOURCE_KEYBOARD)
                    && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
                report.append("  device=").append(id).append(" name=").append(device.getName())
                    .append(" sources=0x").append(Integer.toHexString(device.getSources()))
                    .append(" keyboardType=").append(device.getKeyboardType())
                    .append(" fullKeyboard=").append(full).append('\n');
            }
            report.append('\n');
            // The shortcut manager is usually in this JAR; prioritize it.
            for (String base : new String[]{"/system_ext/framework/", "/system/framework/", "/product/framework/"}) {
                framework(base + "miui-services.jar");
            }
            for (String base : new String[]{"/system_ext/framework/", "/system/framework/", "/product/framework/"}) {
                framework(base + "miui-framework.jar");
            }
            packageCode("com.miui.screenshot");
            packageCode("com.miui.securitycore");
            report.append("\nIncluded code entries: ").append(files)
                .append("\nRaw code bytes: ").append(bytes).append('\n');
            if (files == 0) report.append("NO CODE READABLE: this archive contains a diagnostic report only.\n");
            byte[] text = report.toString().getBytes(StandardCharsets.UTF_8);
            archive.putNextEntry(new ZipEntry("report.txt"));
            archive.write(text);
            archive.closeEntry();
        }

        void framework(String path) throws Exception {
            byte[] data;
            String canonical;
            try {
                File file = new File(path);
                canonical = file.getCanonicalPath();
                if (!seen.add(canonical)) return;
                if (!file.isFile()) {
                    report.append("NOT FOUND/NOT VISIBLE ").append(path).append('\n');
                    return;
                }
                if (file.length() > MAX_PAYLOAD - bytes) {
                    report.append("SKIP size budget ").append(path).append(" length=").append(file.length()).append('\n');
                    return;
                }
                try (InputStream stream = new FileInputStream(file)) {
                    data = readBounded(stream, MAX_PAYLOAD - bytes);
                }
            } catch (IOException | SecurityException error) {
                report.append("READ FAILED ").append(path).append(": ").append(error).append('\n');
                return;
            }
            // Zip write errors propagate so a truncated destination is not reported as success.
            add("framework/" + path.substring(1), data, canonical);
        }

        void packageCode(String name) throws Exception {
            PackageInfo info;
            try {
                info = getPackageManager().getPackageInfo(name, PackageManager.PackageInfoFlags.of(0));
            } catch (PackageManager.NameNotFoundException | SecurityException error) {
                report.append("PACKAGE UNAVAILABLE ").append(name).append(": ").append(error).append('\n');
                return;
            }
            ApplicationInfo app = info.applicationInfo;
            report.append("\nPACKAGE ").append(name).append(" version=").append(info.versionName)
                .append(" code=").append(info.getLongVersionCode()).append('\n');
            if (app == null) return;
            apk(name, "base", app.sourceDir);
            if (app.splitSourceDirs != null) {
                for (int i = 0; i < app.splitSourceDirs.length; i++) apk(name, "split-" + i, app.splitSourceDirs[i]);
            }
        }

        void apk(String pkg, String split, String path) throws Exception {
            ZipFile source;
            try {
                source = new ZipFile(path);
            } catch (IOException | SecurityException error) {
                report.append("APK READ FAILED ").append(path).append(": ").append(error).append('\n');
                return;
            }
            try (ZipFile close = source) {
                Enumeration<? extends ZipEntry> entries = source.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!name.equals("AndroidManifest.xml") && !name.matches("classes(?:[0-9]+)?\\.dex")) continue;
                    byte[] data;
                    if (entry.getSize() > MAX_PAYLOAD - bytes) {
                        report.append("SKIP size budget ").append(path).append('!').append(name).append('\n');
                        continue;
                    }
                    try (InputStream stream = source.getInputStream(entry)) {
                        data = readBounded(stream, MAX_PAYLOAD - bytes);
                    } catch (IOException error) {
                        report.append("ENTRY READ FAILED ").append(path).append('!').append(name).append(": ").append(error).append('\n');
                        continue;
                    }
                    add("packages/" + pkg + "/" + split + "/" + name, data, path + "!" + name);
                }
            }
        }

        void add(String name, byte[] data, String origin) throws Exception {
            archive.putNextEntry(new ZipEntry(name));
            archive.write(data);
            archive.closeEntry();
            bytes += data.length;
            files++;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
            report.append("INCLUDED ").append(origin).append(" -> ").append(name)
                .append(" bytes=").append(data.length).append(" sha256=").append(hex).append('\n');
        }
    }

    static byte[] readBounded(InputStream source, int limit) throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536];
        int size;
        while ((size = source.read(buffer)) != -1) {
            if (size > limit - target.size()) throw new IOException("Entry exceeds remaining export size budget");
            target.write(buffer, 0, size);
        }
        return target.toByteArray();
    }
}
