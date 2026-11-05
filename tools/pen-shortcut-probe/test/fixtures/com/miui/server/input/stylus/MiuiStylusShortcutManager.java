package com.miui.server.input.stylus;

/** Synthetic test input only; this is not Xiaomi firmware. */
public abstract class MiuiStylusShortcutManager {
    public static final int TEST_SECURE = 8192;
    public boolean interceptScreenShotKey(int flags) {
        try {
            if ((flags & TEST_SECURE) != 0) { warn("fixture blocked"); return false; }
            if (flags < 0) throw new IllegalArgumentException("fixture negative");
            return schedule(380L);
        } catch (IllegalArgumentException error) { return false; }
    }
    public abstract boolean isScreenShotKey(int code);
    private static void warn(String message) { System.out.println(message); }
    private boolean schedule(long delay) { return delay > 0; }
    public int remainingPolicy(int code) {
        switch (code) { case 92: return 1; case 93: return 2; case 94: return 3; default: return 0; }
    }
}
