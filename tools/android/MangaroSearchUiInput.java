import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.os.Build;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Shell-only input helper for the dedicated emulator; never packaged in Mangaro. */
public class MangaroSearchUiInput {
    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node) {
        if (node == null || node.getPackageName() == null
                || !"app.manhwaar.reader".contentEquals(node.getPackageName())) {
            return null;
        }
        if ("android.widget.EditText".contentEquals(node.getClassName())) {
            return node;
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo found = find(node.getChild(index));
            if (found != null) return found;
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        if (!"ranchu".equals(Build.HARDWARE) && !"goldfish".equals(Build.HARDWARE)) {
            throw new IllegalStateException("Refusing a non-emulator device");
        }
        if (args.length != 1 || args[0].length() > 4096) {
            throw new IllegalArgumentException("Expected one bounded UTF-8 query");
        }
        HandlerThread thread = new HandlerThread("MangaroQaUi");
        thread.start();
        UiAutomation ui = null;
        boolean connected = false;
        try {
            Class<?> connectionType = Class.forName("android.app.IUiAutomationConnection");
            Object connection = Class.forName("android.app.UiAutomationConnection")
                    .getConstructor().newInstance();
            ui = (UiAutomation) UiAutomation.class.getConstructor(Looper.class, connectionType)
                    .newInstance(thread.getLooper(), connection);
            UiAutomation.class.getMethod("connect").invoke(ui);
            connected = true;
            AccessibilityServiceInfo service = ui.getServiceInfo();
            service.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            ui.setServiceInfo(service);
            Thread.sleep(500);
            AccessibilityNodeInfo field = find(ui.getRootInActiveWindow());
            if (field == null) {
                for (AccessibilityWindowInfo window : ui.getWindows()) {
                    field = find(window.getRoot());
                    if (field != null) break;
                }
            }
            if (field == null) throw new IllegalStateException("No actual Mangaro editable UI field");
            Bundle input = new Bundle();
            input.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    new String(Base64.getDecoder().decode(args[0]), StandardCharsets.UTF_8));
            if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, input)) {
                throw new IllegalStateException("UI text action rejected");
            }
            System.out.println("ACTUAL_UI_TEXT_SET");
        } finally {
            if (connected) UiAutomation.class.getMethod("disconnect").invoke(ui);
            thread.quitSafely();
        }
    }
}
