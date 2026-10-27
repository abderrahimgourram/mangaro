package app.manhwaar.reader.updateqa;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.os.SystemClock;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;

/** Runs only as a separately installed, same-certificate instrumentation APK.
 * The target remains the genuine published, non-debuggable Release APK.
 * Only a new controller's manifest transport is replaced; parsing, comparisons,
 * lifecycle, real dialogs and Android input dispatch remain target production code.
 */
public final class UpdatePolicyProbe extends Instrumentation {
    private Bundle arguments;
    private Object controller;
    private ClassLoader loader;
    private Activity activity;
    private Object repository;

    @Override public void onCreate(Bundle args) {
        arguments = args;
        super.onCreate(args);
        start();
    }

    private Class<?> type(String name) throws Exception { return Class.forName(name, true, loader); }
    private static Object field(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    private static Object call(Object object, String method, Class<?>[] parameters, Object... args) throws Exception {
        Method m = object.getClass().getMethod(method, parameters); m.setAccessible(true); return m.invoke(object, args);
    }
    private void transport(final String body) throws Exception {
        Field clientField = repository.getClass().getDeclaredField("client"); clientField.setAccessible(true);
        Object original = clientField.get(repository);
        Object builder = call(original, "newBuilder", new Class<?>[0]);
        Class<?> interceptor = type("okhttp3.Interceptor");
        Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{interceptor}, (o, method, args) -> {
            if (!method.getName().equals("intercept")) {
                if (method.getName().equals("toString")) return "Exact-public-manifest-QA-transport";
                if (method.getName().equals("hashCode")) return System.identityHashCode(o);
                return o == args[0];
            }
            Object chain = args[0];
            Class<?> chainType = type("okhttp3.Interceptor$Chain");
            Object request = chainType.getMethod("request").invoke(chain);
            Object url = call(request, "url", new Class<?>[0]);
            if (!url.toString().equals("https://mangaro-web.vercel.app/app-version.json")) throw new IOException("QA refuses a non-manifest request");
            Class<?> rb = type("okhttp3.ResponseBody");
            Object responseBody = rb.getMethod("create", type("okhttp3.MediaType"), String.class).invoke(null, null, body);
            Object response = type("okhttp3.Response$Builder").getConstructor().newInstance();
            call(response, "request", new Class<?>[]{type("okhttp3.Request")}, request);
            call(response, "protocol", new Class<?>[]{type("okhttp3.Protocol")}, type("okhttp3.Protocol").getField("HTTP_1_1").get(null));
            call(response, "code", new Class<?>[]{int.class}, 200);
            call(response, "message", new Class<?>[]{String.class}, "Controlled public manifest fixture");
            call(response, "body", new Class<?>[]{rb}, responseBody);
            return call(response, "build", new Class<?>[0]);
        });
        call(builder, "addInterceptor", new Class<?>[]{interceptor}, proxy);
        clientField.set(repository, call(builder, "build", new Class<?>[0]));
    }
    private Object dialog() throws Exception { return field(controller, "dialog"); }
    private boolean showing() throws Exception { Object d = dialog(); return d != null && ((android.app.Dialog)d).isShowing(); }
    private void inputOutside() {
        long t = SystemClock.uptimeMillis();
        getUiAutomation().injectInputEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 5, getTargetContext().getResources().getDisplayMetrics().heightPixels / 2, 0), true);
        getUiAutomation().injectInputEvent(MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, 5, getTargetContext().getResources().getDisplayMetrics().heightPixels / 2, 0), true);
    }
    private void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            loader = getTargetContext().getClassLoader();
            int code = getTargetContext().getPackageManager().getPackageInfo(getTargetContext().getPackageName(), 0).versionCode;
            Intent intent = new Intent().setClassName(getTargetContext().getPackageName(), "eu.kanade.tachiyomi.ui.main.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = startActivitySync(intent);
            waitForIdleSync();
            Constructor<?> ctor = type("eu.kanade.tachiyomi.data.updater.MandatoryUpdateController").getDeclaredConstructor(); ctor.setAccessible(true);
            controller = ctor.newInstance(); repository = field(controller, "repository");
            int minimum = Integer.parseInt(arguments.getString("minimum", "49"));
            JSONObject json = new JSONObject();
            json.put("latestVersionName", "2.5.9"); json.put("latestVersionCode", 49);
            json.put("minSupportedVersionCode", minimum); json.put("forceUpdate", true);
            json.put("downloadUrl", "https://github.com/abderrahimgourram/mangaro/releases/download/v2.5.9/Mangaro-Universal.apk");
            transport(json.toString());
            runOnMainSync(() -> {
                try {
                    call(controller, "onActivityResumed", new Class<?>[]{Activity.class}, activity);
                    call(controller, "onStart", new Class<?>[]{type("androidx.lifecycle.LifecycleOwner")}, activity);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            boolean expected = code < minimum && code < 49;
            long until = SystemClock.uptimeMillis() + 9000;
            while (SystemClock.uptimeMillis() < until) {
                if (expected && showing()) break;
                Thread.sleep(100);
            }
            check(showing() == expected, "Actual Release gate differs from minimum policy for code " + code);
            if (expected) {
                Object d = dialog();
                TextView message = ((android.app.Dialog)d).findViewById(android.R.id.message);
                check(message != null && message.getText().toString().contains("2.5.9"), "Version missing from actual update dialog");
                getUiAutomation().injectInputEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK), true);
                getUiAutomation().injectInputEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK), true); Thread.sleep(200);
                check(showing(), "Back dismissed mandatory update");
                inputOutside(); Thread.sleep(200); check(showing(), "Outside touch dismissed mandatory update");
                result.putString("mandatoryMessage", message.getText().toString());
                result.putBoolean("backAndOutsideCannotDismiss", true);
            }
            Bitmap image = getUiAutomation().takeScreenshot(); check(image != null, "No actual screenshot");
            File dir = new File(getTargetContext().getFilesDir(), "update-policy-qa"); check(dir.mkdirs() || dir.isDirectory(), "No QA output directory");
            File screenshot = new File(dir, "code" + code + "-min" + minimum + "-online" + ".png");
            try (FileOutputStream output = new FileOutputStream(screenshot)) { image.compress(Bitmap.CompressFormat.PNG, 100, output); }
            result.putInt("actualVersionCode", code); result.putInt("minimum", minimum);
            result.putBoolean("mandatoryVisible", showing());
            result.putString("screenshot", screenshot.getAbsolutePath()); result.putString("status", "PASS");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("status", "FAIL"); result.putString("failure", failure.toString());
            failure.printStackTrace(); finish(Activity.RESULT_CANCELED, result);
        }
    }
}
