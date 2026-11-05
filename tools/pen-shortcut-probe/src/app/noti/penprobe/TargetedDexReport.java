package app.noti.penprobe;

import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.formatter.DexFormatter;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.instruction.formats.ArrayPayload;
import com.android.tools.smali.dexlib2.iface.instruction.formats.UnknownInstruction;
import java.io.File;
import java.io.IOException;
import java.util.*;

/** Offline, read-only disassembly of a fixed set of pen-policy methods. */
public final class TargetedDexReport {
    private static final int MAX_SECTION = 180000;
    private final LinkedHashMap<String, StringBuilder> sections = new LinkedHashMap<>();
    private int matches;
    public TargetedDexReport() {
        for (String name : new String[]{"Summary", "Screenshot handler", "Dispatch and delay", "Queue policy", "Keyboard bypass", "Writing reference", "Remaining pen policy"}) {
            sections.put(name, new StringBuilder());
        }
    }
    public void note(String text) { sections.get("Summary").append(text).append('\n'); }
    public int getMatchCount() { return matches; }

    public void inspect(File source) throws IOException {
        MultiDexContainer<?> container = DexFileFactory.loadDexContainer(source, Opcodes.forApi(36));
        List<String> entries = container.getDexEntryNames();
        note("Source: " + source + "; dex entries=" + entries.size());
        for (String entry : entries) {
            MultiDexContainer.DexEntry<?> dexEntry = container.getEntry(entry);
            if (dexEntry == null) { note("Missing entry: " + entry); continue; }
            DexFile dex = dexEntry.getDexFile();
            for (ClassDef type : dex.getClasses()) {
                String name = type.getType();
                boolean stylus = name.endsWith("/MiuiStylusShortcutManager;") || name.endsWith("/MiuiStylusPageKeyListener;");
                boolean queue = name.endsWith("/BaseMiuiPhoneWindowManager;");
                boolean keyboard = name.endsWith("/MiuiKeyInterceptExtend;");
                if (!stylus && !queue && !keyboard) continue;
                note("FOUND " + name + " in " + entry);
                for (Field field : type.getFields()) {
                    if (field.getInitialValue() != null) note("  FIELD " + DexFormatter.INSTANCE.getFieldDescriptor(field)
                        + " = " + DexFormatter.INSTANCE.getEncodedValue(field.getInitialValue()));
                }
                for (Method method : type.getMethods()) {
                    String section = section(method.getName(), stylus, queue, keyboard);
                    if (section == null) continue;
                    matches++;
                    StringBuilder text = sections.get(section);
                    text.append("\nSOURCE ").append(source).append('!').append(entry).append('\n');
                    writeMethod(text, method);
                    note("  METHOD " + method.getName() + " -> " + section);
                }
            }
        }
    }

    private static String section(String method, boolean stylus, boolean queue, boolean keyboard) {
        if (stylus) {
            if (Arrays.asList("interceptScreenShotKey", "isScreenShotKey", "takePartialScreenshot",
                    "triggerScreenShotKeyFunction").contains(method)) return "Screenshot handler";
            if (Arrays.asList("shouldInterceptKey", "needInterceptBeforeDispatching", "getDelayTime",
                    "updateGameModeSettings").contains(method)) return "Dispatch and delay";
            if (Arrays.asList("interceptQuickNoteKeyDownWhenScreenOn", "<clinit>").contains(method)) return "Writing reference";
            return "Remaining pen policy";
        }
        if (queue && (method.contains("interceptKeyBeforeQueueing") || method.equals("interceptKeyBeforeDispatching"))) return "Queue policy";
        if (keyboard && (method.startsWith("getKeyInterceptType") || method.equals("initSystemSpecialWindow"))) return "Keyboard bypass";
        return null;
    }

    private static void writeMethod(StringBuilder text, Method method) {
        text.append("METHOD ").append(DexFormatter.INSTANCE.getMethodDescriptor(method)).append('\n');
        MethodImplementation implementation = method.getImplementation();
        if (implementation == null) { text.append("  NO CODE (abstract/native/stripped)\n"); return; }
        text.append("registers=").append(implementation.getRegisterCount())
            .append(" flags=0x").append(Integer.toHexString(method.getAccessFlags())).append('\n');
        int address = 0;
        int count = 0;
        for (Instruction instruction : implementation.getInstructions()) {
            if (++count > 10000 || text.length() >= MAX_SECTION) {
                text.append("\nTRUNCATED: method/section limit reached; absence beyond this point is not evidence.\n");
                break;
            }
            if (instruction instanceof UnknownInstruction) {
                text.append("UNSUPPORTED OPCODE at ").append(address).append(": 0x")
                    .append(Integer.toHexString(((UnknownInstruction) instruction).getOriginalOpcode()))
                    .append(". Decoding stopped; this method is incomplete.\n");
                break;
            }
            text.append(String.format(Locale.ROOT, "%04x: ", address)).append(instruction.getOpcode().name);
            if (instruction instanceof RegisterRangeInstruction) {
                RegisterRangeInstruction r = (RegisterRangeInstruction) instruction;
                text.append(" {v").append(r.getStartRegister()).append(" .. +").append(r.getRegisterCount()).append('}');
            } else if (instruction instanceof FiveRegisterInstruction) {
                FiveRegisterInstruction r = (FiveRegisterInstruction) instruction;
                int[] regs = {r.getRegisterC(), r.getRegisterD(), r.getRegisterE(), r.getRegisterF(), r.getRegisterG()};
                text.append(" {");
                for (int i = 0; i < r.getRegisterCount(); i++) { if (i > 0) text.append(','); text.append('v').append(regs[i]); }
                text.append('}');
            } else if (instruction instanceof ThreeRegisterInstruction) {
                ThreeRegisterInstruction r = (ThreeRegisterInstruction) instruction;
                text.append(" v").append(r.getRegisterA()).append(",v").append(r.getRegisterB()).append(",v").append(r.getRegisterC());
            } else if (instruction instanceof TwoRegisterInstruction) {
                TwoRegisterInstruction r = (TwoRegisterInstruction) instruction;
                text.append(" v").append(r.getRegisterA()).append(",v").append(r.getRegisterB());
            } else if (instruction instanceof OneRegisterInstruction) text.append(" v").append(((OneRegisterInstruction) instruction).getRegisterA());
            if (instruction instanceof WideLiteralInstruction) {
                long literal = ((WideLiteralInstruction) instruction).getWideLiteral();
                text.append(", #").append(literal).append("/0x").append(Long.toHexString(literal));
            }
            if (instruction instanceof ReferenceInstruction) text.append(", ")
                .append(DexFormatter.INSTANCE.getReference(((ReferenceInstruction) instruction).getReference()));
            if (instruction instanceof DualReferenceInstruction) text.append(", ")
                .append(DexFormatter.INSTANCE.getReference(((DualReferenceInstruction) instruction).getReference2()));
            if (instruction instanceof OffsetInstruction) text.append(String.format(Locale.ROOT, ", ->%04x", address + ((OffsetInstruction) instruction).getCodeOffset()));
            if (instruction instanceof SwitchPayload) {
                text.append(" {keys:relative offsets from SWITCH opcode");
                for (SwitchElement element : ((SwitchPayload) instruction).getSwitchElements())
                    text.append(' ').append(element.getKey()).append(':').append(element.getOffset());
                text.append('}');
            }
            if (instruction instanceof ArrayPayload) {
                ArrayPayload array = (ArrayPayload) instruction;
                List<Number> values = array.getArrayElements();
                text.append(" width=").append(array.getElementWidth()).append(" values=")
                    .append(values.subList(0, Math.min(64, values.size())));
                if (values.size() > 64) text.append(" [array values truncated]");
            }
            text.append('\n');
            address += instruction.getCodeUnits();
        }
        for (TryBlock<? extends ExceptionHandler> block : implementation.getTryBlocks()) {
            text.append("TRY ").append(block.getStartCodeAddress()).append(" +").append(block.getCodeUnitCount());
            for (ExceptionHandler handler : block.getExceptionHandlers()) text.append(" catch ").append(handler.getExceptionType())
                .append(" ->").append(handler.getHandlerCodeAddress());
            text.append('\n');
        }
    }

    public LinkedHashMap<String, String> finish() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        note("Selected methods: " + matches + ". Raw DEX instructions; no code was executed or modified.");
        for (Map.Entry<String, StringBuilder> entry : sections.entrySet()) {
            String text = entry.getValue().length() == 0 ? "NOT FOUND in readable/scanned sources. This is not proof the method or handler is absent.\n" : entry.getValue().toString();
            result.put(entry.getKey(), text);
        }
        return result;
    }
}
