import java.lang.reflect.Method;

/** Read-only comparison of DisplayManager's selected mode and SF's active mode. */
public final class PhysicalModeProbe {
    public static void main(String[] args) throws Exception {
        Class<?> sc = Class.forName("android.view.SurfaceControl");
        for (Method method : sc.getDeclaredMethods()) {
            if (method.getName().contains("DynamicDisplayInfo") ||
                    method.getName().equals("getPhysicalDisplayToken")) System.out.println(method);
        }
        Class<?> at = Class.forName("android.app.ActivityThread");
        Class.forName("android.os.Looper").getMethod("prepareMainLooper").invoke(null);
        Object thread = at.getMethod("systemMain").invoke(null);
        Object context = at.getMethod("getSystemContext").invoke(thread);
        Object dm = Class.forName("android.content.Context").getMethod("getSystemService", String.class)
                .invoke(context, "display");
        Object[] displays = (Object[]) dm.getClass().getMethod("getDisplays").invoke(dm);
        Class<?> displayClass = Class.forName("android.view.Display");
        for (Object display : displays) {
            int logical = (Integer) displayClass.getMethod("getDisplayId").invoke(display);
            Object address = displayClass.getMethod("getAddress").invoke(display);
            if (address == null) continue;
            Method physicalId;
            try { physicalId = address.getClass().getMethod("getPhysicalDisplayId"); }
            catch (NoSuchMethodException virtual) { continue; }
            long physical = (Long) physicalId.invoke(address);
            Object selected = displayClass.getMethod("getMode").invoke(display);
            System.out.println("DISPLAY logical=" + logical + " physical=" + physical + " DMS=" + selected);
            Object info;
            try {
                info = sc.getMethod("getDynamicDisplayInfo", long.class).invoke(null, physical);
            } catch (NoSuchMethodException missing) {
                Object token = sc.getMethod("getPhysicalDisplayToken", long.class).invoke(null, physical);
                info = sc.getMethod("getDynamicDisplayInfo", Class.forName("android.os.IBinder"))
                        .invoke(null, token);
            }
            if (info == null) { System.out.println("SF=unavailable"); continue; }
            int active = info.getClass().getField("activeDisplayModeId").getInt(info);
            for (Object mode : (Object[]) info.getClass().getField("supportedDisplayModes").get(info)) {
                if (mode.getClass().getField("id").getInt(mode) == active) {
                    System.out.println("SF=" + mode);
                    if (args.length > 0 && logical != 0) {
                        Method read = Class.forName("com.extbridge.daemon.ExtBridge")
                                .getDeclaredMethod("readActivePhysicalMode", displayClass);
                        read.setAccessible(true);
                        Object observed = read.invoke(null, display);
                        float fps = (Float) observed.getClass().getMethod("getRefreshRate").invoke(observed);
                        float expected = Float.parseFloat(args[0]);
                        System.out.println("DAEMON_PHYSICAL=" + observed);
                        if (Math.abs(fps - expected) > 0.02f) throw new AssertionError("Incorrect physical fps " + fps);
                        if (args.length > 1) {
                            float selectedFps = (Float) selected.getClass().getMethod("getRefreshRate").invoke(selected);
                            if (Math.abs(selectedFps - fps) < 0.02f) throw new AssertionError("Mismatch regression not reproduced");
                        }
                        System.out.println("DAEMON_MODE_REGRESSION=PASS");
                    }
                }
            }
        }
    }
}
