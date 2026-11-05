package app.noti.penprobe;

import java.io.File;
import java.util.Map;

public final class TargetedDexReportTest {
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 2; i++) {
            TargetedDexReport report = new TargetedDexReport();
            report.inspect(new File(args[i]));
            Map<String, String> sections = report.finish();
            String screenshot = sections.get("Screenshot handler");
            require(screenshot.contains("->interceptScreenShotKey(I)Z"), "method signature");
            if (i == 1) require(screenshot.contains("!classes2.dex"), "secondary dex scanned");
            require(screenshot.contains("#8192/0x2000"), "flag literal decoded");
            require(screenshot.contains("#380/0x17c"), "wide timer literal decoded");
            require(screenshot.contains("fixture blocked"), "string reference resolved");
            require(screenshot.contains("->schedule(J)Z"), "method reference resolved");
            require(screenshot.contains("if-eqz") && screenshot.contains("->"), "branch addresses");
            require(screenshot.contains("TRY ") && screenshot.contains("Ljava/lang/IllegalArgumentException;"), "exception range");
            require(screenshot.contains("NO CODE (abstract/native/stripped)"), "missing code explicit");
            require(sections.get("Remaining pen policy").contains("relative offsets from SWITCH opcode"), "switch payload semantics");
            require(!sections.toString().contains("UNRELATED_SENTINEL_NOT_FOR_REPORT"), "class scope");
            require(sections.get("Queue policy").contains("NOT FOUND"), "absence explicit");
        }
        try {
            new TargetedDexReport().inspect(new File(args[2]));
            throw new AssertionError("Corrupt input was accepted");
        } catch (java.io.IOException | RuntimeException expected) {
            // The app must surface this failure in Summary, not fabricate code.
        }
        System.out.println("DEX/JAR fixture checks passed: flags, timer, references, branches, switch, exceptions, scope, and corrupt input.");
    }
}
