package app.noti.penprobe;

import android.app.Application;
import java.io.PrintWriter;
import java.io.StringWriter;

/** Keep a rejected window experiment readable after reopening, without logcat. */
public final class ProbeApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                StringWriter stack = new StringWriter();
                error.printStackTrace(new PrintWriter(stack));
                String report = getSharedPreferences("probe", MODE_PRIVATE).getString("report", "");
                getSharedPreferences("probe", MODE_PRIVATE).edit()
                    .putString("report", report + "\nPROBE FAILED\n" + stack).commit();
            } catch (Throwable ignored) {
                // Preserve Android's crash handling even if saving the report fails.
            }
            if (previous != null) previous.uncaughtException(thread, error);
            else android.os.Process.killProcess(android.os.Process.myPid());
        });
    }
}
