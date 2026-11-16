package com.extbridge.probe;

import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Process;
import android.util.DisplayMetrics;
import android.view.Display;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 本机驱动/硬件信息采集器。
 *
 * 设计目标：
 *  - 纯 Java + 公开 Android API，既能被 root app_process 守护进程调用，也能被普通 App 调用；
 *  - 只用文件读取和小范围 shell 命令，不依赖任何厂商私有接口；
 *  - 输出稳定 JSON，方便 UI 展示、导出和反馈问题。
 *
 * root 模式下可以读取 /sys/kernel/debug、DRM EDID、Type-C、dmesg 等
 * 普通 App 无法访问的节点；非 root 时对应字段会缺失或标记 denied。
 */
public final class DriverProbe {
    public static final int SCHEMA = 1;
    private static final int MAX_TEXT = 48 * 1024;
    private static final int MAX_EDID = 4096;
    private static final int MAX_INPUT = 64;
    private static final int MAX_USB = 32;

    private DriverProbe() {}

    public static String collectJson(DisplayManager dm, String hostMode) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", SCHEMA);
        root.put("generatedAt", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date()));
        root.put("hostMode", hostMode == null ? "app" : hostMode);
        root.put("uid", Process.myUid());
        root.put("isRoot", Process.myUid() == 0);
        root.put("selinux", trim(readText(new File("/proc/self/attr/current"), 512)));
        root.put("suBinary", new File("/system/bin/su").exists() || new File("/system/xbin/su").exists());
        root.put("device", deviceInfo());
        root.put("drm", drmInfo());
        root.put("displays", displayInfo(dm));
        root.put("gpu", gpuInfo());
        root.put("panel", panelInfo());
        root.put("inputs", inputInfo());
        root.put("typec", typecInfo());
        root.put("usb", usbInfo());
        root.put("debug", debugInfo());
        return Json.write(root);
    }

    /* ------------------------------ device ------------------------------ */

    private static Map<String, Object> deviceInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("manufacturer", Build.MANUFACTURER);
        m.put("brand", Build.BRAND);
        m.put("model", Build.MODEL);
        m.put("device", Build.DEVICE);
        m.put("board", Build.BOARD);
        m.put("hardware", Build.HARDWARE);
        m.put("product", Build.PRODUCT);
        m.put("fingerprint", Build.FINGERPRINT);
        m.put("androidRelease", Build.VERSION.RELEASE);
        m.put("sdk", Build.VERSION.SDK_INT);
        m.put("buildId", Build.ID);
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                m.put("socManufacturer", Build.SOC_MANUFACTURER);
                m.put("socModel", Build.SOC_MODEL);
            }
        } catch (Throwable ignored) {}
        m.put("abi", Arrays.toString(Build.SUPPORTED_ABIS));
        m.put("kernelRelease", System.getProperty("os.version"));
        m.put("osArch", System.getProperty("os.arch"));
        putText(m, "kernelVersion", new File("/proc/version"), MAX_TEXT);
        putText(m, "cmdline", new File("/proc/cmdline"), MAX_TEXT);
        Map<String, Object> props = new LinkedHashMap<>();
        String[] keys = {
                "ro.product.model", "ro.product.device", "ro.product.name", "ro.board.platform", "ro.hardware",
                "ro.build.characteristics", "ro.boot.hardware.platform",
                "ro.soc.model", "ro.soc.manufacturer", "ro.boot.hardware", "ro.build.version.release",
                "ro.build.version.sdk", "ro.build.display.id", "ro.vendor.build.id",
                "ro.vendor.build.version.release", "persist.sys.locale", "ro.boot.project_name",
                "ro.boot.hwlevel", "ro.boot.hwc", "ro.mi.os.version.name", "ro.mi.os.version.code"
        };
        for (String k : keys) {
            String v = getProp(k);
            if (v != null && !v.isEmpty()) props.put(k, v);
        }
        m.put("roProps", props);
        return m;
    }

    private static volatile java.lang.reflect.Method sSysPropGet;
    private static volatile boolean sSysPropFailed = false;

    private static String getProp(String key) {
        if (!sSysPropFailed) {
            try {
                java.lang.reflect.Method m = sSysPropGet;
                if (m == null) {
                    Class<?> c = Class.forName("android.os.SystemProperties");
                    m = c.getMethod("get", String.class);
                    sSysPropGet = m;
                }
                Object v = m.invoke(null, key);
                return v == null ? null : String.valueOf(v).trim();
            } catch (Throwable t) {
                sSysPropFailed = true;
            }
        }
        String v = run("/system/bin/getprop " + shellQuote(key), 1200);
        return v == null ? null : v.trim();
    }

    /* -------------------------------- drm ------------------------------- */

    private static Map<String, Object> drmInfo() {
        Map<String, Object> out = new LinkedHashMap<>();
        File drm = new File("/sys/class/drm");
        List<String> names = listNames(drm);
        out.put("version", trim(readText(new File(drm, "version"), 256)));
        List<Object> connectors = new ArrayList<>();
        for (String name : names) {
            File dir = new File(drm, name);
            File statusFile = new File(dir, "status");
            if (!statusFile.exists()) continue;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", name);
            c.put("status", trim(readText(statusFile, 256)));
            putText(c, "enabled", new File(dir, "enabled"), 256);
            putText(c, "dpms", new File(dir, "dpms"), 256);
            putText(c, "connectorId", new File(dir, "connector_id"), 256);
            putText(c, "linkStatus", new File(dir, "link_status"), 256);
            putText(c, "maxBpc", new File(dir, "max_bpc"), 256);
            List<String> modes = new ArrayList<>();
            String rawModes = readText(new File(dir, "modes"), 8192);
            if (rawModes != null) {
                for (String line : rawModes.split("\n")) {
                    String t = line.trim();
                    if (!t.isEmpty() && modes.size() < 96) modes.add(t);
                }
            }
            c.put("modes", modes);
            File edidFile = new File(dir, "edid");
            byte[] edid = readBytes(edidFile, MAX_EDID);
            c.put("edidLength", edid == null ? 0 : edid.length);
            if (edid != null && edid.length >= 128) {
                c.put("edid", parseEdid(edid));
                c.put("edidSha1Safe", sha1Safe(edid));
            }
            // connector 目录里常见的厂商节点，存在才收集
            List<String> extra = new ArrayList<>();
            String[] extraNames = {"panel_name", "panel", "hdr_cap", "output_type", "crtc_id"};
            for (String en : extraNames) {
                File f = new File(dir, en);
                if (f.exists()) {
                    String v = trim(readText(f, 1024));
                    if (v != null) extra.add(en + "=" + v);
                }
            }
            if (!extra.isEmpty()) c.put("sysfs", extra);
            connectors.add(c);
        }
        out.put("connectors", connectors);
        out.put("cardPath", "/sys/class/drm/card0");
        putText(out, "uevent", new File("/sys/class/drm/card0/device/uevent"), MAX_TEXT);
        return out;
    }

    /** 解析 EDID 基础字段；解析失败返回 null，不抛异常。 */
    public static Map<String, Object> parseEdid(byte[] e) {
        try {
            if (e == null || e.length < 128) return null;
            if ((e[0] & 0xff) != 0x00 || (e[1] & 0xff) != 0xff || (e[7] & 0xff) != 0x00) return null;
            Map<String, Object> m = new LinkedHashMap<>();
            int mfg = ((e[8] & 0xff) << 8) | (e[9] & 0xff);
            StringBuilder name = new StringBuilder();
            for (int shift : new int[]{10, 5, 0}) {
                int v = (mfg >> shift) & 0x1f;
                name.append(v >= 1 && v <= 26 ? (char) ('A' + v - 1) : '?');
            }
            m.put("manufacturerId", name.toString());
            m.put("productCode", String.format(Locale.US, "0x%04X", ((e[10] & 0xff) | ((e[11] & 0xff) << 8))));
            long serial = (e[12] & 0xffL) | ((e[13] & 0xffL) << 8) | ((e[14] & 0xffL) << 16) | ((e[15] & 0xffL) << 24);
            m.put("serial", serial);
            m.put("week", e[16] & 0xff);
            m.put("year", 1990 + (e[17] & 0xff));
            m.put("version", (e[18] & 0xff) + "." + (e[19] & 0xff));
            m.put("widthCm", e[21] & 0xff);
            m.put("heightCm", e[22] & 0xff);
            m.put("gamma", Math.round(((e[23] & 0xff) + 100) / 100.0 * 100) / 100.0);
            m.put("extensions", e[126] & 0xff);
            List<Object> descriptors = new ArrayList<>();
            String productName = null;
            String serialText = null;
            Map<String, Object> range = null;
            for (int i = 0; i < 4; i++) {
                int o = 54 + i * 18;
                int clock = ((e[o + 1] & 0xff) << 8) | (e[o] & 0xff);
                if (clock != 0) {
                    int hActive = ((e[o + 4] & 0xf0) << 4) | (e[o + 2] & 0xff);
                    int hBlank = ((e[o + 4] & 0x0f) << 8) | (e[o + 3] & 0xff);
                    int vActive = ((e[o + 7] & 0xf0) << 4) | (e[o + 5] & 0xff);
                    int vBlank = ((e[o + 7] & 0x0f) << 8) | (e[o + 6] & 0xff);
                    int hTotal = hActive + hBlank;
                    int vTotal = vActive + vBlank;
                    double refresh = (clock * 10.0 * 1000.0) / (hTotal * (double) vTotal);
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("pixelClockKHz", clock * 10);
                    d.put("width", hActive);
                    d.put("height", vActive);
                    d.put("refresh", Math.round(refresh * 100) / 100.0);
                    descriptors.add(d);
                } else {
                    int tag = e[o + 3] & 0xff;
                    if (tag == 0xfc) {
                        productName = ascii(e, o + 5, 13);
                    } else if (tag == 0xff) {
                        serialText = ascii(e, o + 5, 13);
                    } else if (tag == 0xfd) {
                        range = new LinkedHashMap<>();
                        range.put("minVerticalHz", e[o + 5] & 0xff);
                        range.put("maxVerticalHz", e[o + 6] & 0xff);
                        range.put("minHorizontalKHz", e[o + 7] & 0xff);
                        range.put("maxHorizontalKHz", e[o + 8] & 0xff);
                        range.put("maxPixelClockMHz", (e[o + 9] & 0xff) * 10);
                    }
                }
            }
            if (productName != null && !productName.isEmpty()) m.put("productName", productName);
            if (serialText != null && !serialText.isEmpty()) m.put("serialText", serialText);
            if (range != null) m.put("range", range);
            m.put("detailedTimings", descriptors);
            if (e.length >= 132 && (e[128] & 0xff) == 0x02) {
                Map<String, Object> cta = new LinkedHashMap<>();
                cta.put("revision", e[129] & 0xff);
                cta.put("dtdOffset", e[130] & 0xff);
                cta.put("flags", String.format(Locale.US, "0x%02X", e[131] & 0xff));
                m.put("cta", cta);
            }
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean hasToken(String s, String token) {
        int from = 0;
        while (true) {
            int i = s.indexOf(token, from);
            if (i < 0) return false;
            int before = i - 1;
            int after = i + token.length();
            boolean leftOk = before < 0 || !Character.isLetterOrDigit(s.charAt(before));
            boolean rightOk = after >= s.length() || !Character.isLetterOrDigit(s.charAt(after));
            if (leftOk && rightOk) return true;
            from = i + 1;
        }
    }

    private static String ascii(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < off + len && i < b.length; i++) {
            int c = b[i] & 0xff;
            if (c == 0x0a || c == 0x0d) break;
            if (c >= 0x20 && c < 0x7f) sb.append((char) c);
        }
        return sb.toString().trim();
    }

    /* ------------------------------ displays ---------------------------- */

    private static List<Object> displayInfo(DisplayManager dm) {
        List<Object> out = new ArrayList<>();
        if (dm == null) return out;
        try {
            Display[] displays = dm.getDisplays();
            if (displays == null) return out;
            for (Display d : displays) {
                if (d == null) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", d.getDisplayId());
                m.put("name", d.getName());
                m.put("valid", d.isValid());
                m.put("type", intOrNull(d, "getType"));
                m.put("flags", d.getFlags());
                m.put("state", d.getState());
                m.put("rotation", d.getRotation());
                Display.Mode mode = d.getMode();
                if (mode != null) {
                    Map<String, Object> mm = new LinkedHashMap<>();
                    mm.put("modeId", mode.getModeId());
                    mm.put("width", mode.getPhysicalWidth());
                    mm.put("height", mode.getPhysicalHeight());
                    mm.put("refresh", Math.round(mode.getRefreshRate() * 100) / 100.0);
                    m.put("mode", mm);
                }
                DisplayMetrics metrics = new DisplayMetrics();
                try {
                    d.getRealMetrics(metrics);
                    Map<String, Object> dm2 = new LinkedHashMap<>();
                    dm2.put("widthPixels", metrics.widthPixels);
                    dm2.put("heightPixels", metrics.heightPixels);
                    dm2.put("densityDpi", metrics.densityDpi);
                    dm2.put("xdpi", Math.round(metrics.xdpi * 10) / 10.0);
                    dm2.put("ydpi", Math.round(metrics.ydpi * 10) / 10.0);
                    m.put("realMetrics", dm2);
                } catch (Throwable ignored) {}
                try {
                    Display.Mode[] modes = d.getSupportedModes();
                    List<Object> ms = new ArrayList<>();
                    if (modes != null) {
                        for (Display.Mode mm2 : modes) {
                            if (mm2 == null || ms.size() >= 96) continue;
                            Map<String, Object> x = new LinkedHashMap<>();
                            x.put("modeId", mm2.getModeId());
                            x.put("width", mm2.getPhysicalWidth());
                            x.put("height", mm2.getPhysicalHeight());
                            x.put("refresh", Math.round(mm2.getRefreshRate() * 100) / 100.0);
                            ms.add(x);
                        }
                    }
                    m.put("supportedModes", ms);
                } catch (Throwable ignored) {}
                try {
                    String uid = (String) Display.class.getMethod("getUniqueId").invoke(d);
                    if (uid != null) m.put("uniqueId", uid);
                } catch (Throwable ignored) {}
                out.add(m);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static Integer intOrNull(Display d, String method) {
        try {
            Object v = Display.class.getMethod(method).invoke(d);
            return v instanceof Integer ? (Integer) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /* -------------------------------- gpu ------------------------------- */

    private static Map<String, Object> gpuInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        String[][] paths = {
                {"model", "/sys/class/kgsl/kgsl-3d0/gpu_model"},
                {"busyPercent", "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"},
                {"curFreq", "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq"},
                {"maxFreq", "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq"},
                {"minFreq", "/sys/class/kgsl/kgsl-3d0/devfreq/min_freq"},
                {"governor", "/sys/class/kgsl/kgsl-3d0/devfreq/governor"},
                {"gpuClock", "/sys/class/kgsl/kgsl-3d0/gpuclk"},
                {"maliInfo", "/sys/class/misc/mali0/device/gpuinfo"},
                {"drmVersion", "/sys/class/drm/version"},
        };
        for (String[] p : paths) {
            String v = trim(readText(new File(p[1]), 2048));
            if (v != null) m.put(p[0], v);
        }
        List<Object> devfreq = new ArrayList<>();
        File df = new File("/sys/class/devfreq");
        for (String n : listNames(df)) {
            if (devfreq.size() >= 24) break;
            if (!n.toLowerCase(Locale.US).contains("gpu") && !n.contains("3d0") && !n.toLowerCase(Locale.US).contains("kgsl")) continue;
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("name", n);
            putText(x, "curFreq", new File(df, n + "/cur_freq"), 256);
            putText(x, "maxFreq", new File(df, n + "/max_freq"), 256);
            putText(x, "governor", new File(df, n + "/governor"), 256);
            devfreq.add(x);
        }
        if (!devfreq.isEmpty()) m.put("devfreq", devfreq);
        return m;
    }

    /* ------------------------------- panel ------------------------------ */

    private static Map<String, Object> panelInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        putText(m, "deviceTreeModel", new File("/proc/device-tree/model"), 1024);
        putText(m, "deviceTreeCompatible", new File("/proc/device-tree/compatible"), 2048);
        putText(m, "dsiPanelName", new File("/sys/class/drm/card0-DSI-1/panel_name"), 1024);
        putText(m, "dsiPanel", new File("/sys/class/drm/card0-DSI-1/panel"), 1024);
        List<Object> backlights = new ArrayList<>();
        File bl = new File("/sys/class/backlight");
        for (String n : listNames(bl)) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("name", n);
            putText(x, "type", new File(bl, n + "/type"), 256);
            putText(x, "maxBrightness", new File(bl, n + "/max_brightness"), 256);
            putText(x, "brightness", new File(bl, n + "/brightness"), 256);
            putText(x, "actualBrightness", new File(bl, n + "/actual_brightness"), 256);
            backlights.add(x);
        }
        m.put("backlights", backlights);
        List<Object> leds = new ArrayList<>();
        File ledsDir = new File("/sys/class/leds");
        for (String n : listNames(ledsDir)) {
            String low = n.toLowerCase(Locale.US);
            if (!low.contains("lcd") && !low.contains("backlight") && !low.contains("display")) continue;
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("name", n);
            putText(x, "maxBrightness", new File(ledsDir, n + "/max_brightness"), 256);
            putText(x, "brightness", new File(ledsDir, n + "/brightness"), 256);
            leds.add(x);
        }
        if (!leds.isEmpty()) m.put("leds", leds);
        // 面板常见的 vendor 节点
        String[] vendorNodes = {
                "/sys/class/drm/card0-DSI-1/mode", "/sys/class/graphics/fb0/name",
                "/sys/class/graphics/fb0/virtual_size", "/sys/class/graphics/fb0/bits_per_pixel"
        };
        Map<String, Object> nodes = new LinkedHashMap<>();
        for (String p : vendorNodes) {
            String v = trim(readText(new File(p), 1024));
            if (v != null) nodes.put(p, v);
        }
        if (!nodes.isEmpty()) m.put("nodes", nodes);
        return m;
    }

    /* ------------------------------- input ------------------------------ */

    private static List<Object> inputInfo() {
        List<Object> out = new ArrayList<>();
        String text = readText(new File("/proc/bus/input/devices"), MAX_TEXT);
        if (text == null) return out;
        for (String block : text.split("\n\n")) {
            if (out.size() >= MAX_INPUT) break;
            Map<String, Object> m = new LinkedHashMap<>();
            Map<String, Object> caps = new LinkedHashMap<>();
            for (String line : block.split("\n")) {
                if (line.length() < 3) continue;
                char type = line.charAt(0);
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String val = line.substring(colon + 1).trim();
                if (type == 'I') {
                    for (String kv : val.split(" ")) {
                        int eq = kv.indexOf('=');
                        if (eq > 0) m.put(kv.substring(0, eq).toLowerCase(Locale.US), kv.substring(eq + 1));
                    }
                } else if (type == 'N') {
                    m.put("name", stripQuotes(val));
                } else if (type == 'P') {
                    m.put("phys", val);
                } else if (type == 'S') {
                    m.put("sysfs", val);
                } else if (type == 'U') {
                    m.put("uniq", val);
                } else if (type == 'H') {
                    m.put("handlers", val);
                } else if (type == 'B') {
                    int eq = val.indexOf('=');
                    if (eq > 0) caps.put(val.substring(0, eq), val.substring(eq + 1));
                }
            }
            if (!m.isEmpty()) {
                if (!caps.isEmpty()) m.put("capabilities", caps);
                out.add(m);
            }
        }
        return out;
    }

    /* ------------------------------- typec ------------------------------ */

    private static Map<String, Object> typecInfo() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> ports = new ArrayList<>();
        File typec = new File("/sys/class/typec");
        for (String n : listNames(typec)) {
            File d = new File(typec, n);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", n);
            String[] files = {
                    "data_role", "power_role", "port_type", "svid", "vconn_source",
                    "usb_power_delivery_revision", "usb_typec_revision", "orientation",
                    "supported_roles", "supported_accessory_modes"
            };
            for (String f : files) putText(m, f, new File(d, f), 512);
            File partner = new File(d, "partner");
            if (partner.isDirectory()) {
                Map<String, Object> p = new LinkedHashMap<>();
                putText(p, "id_header", new File(partner, "id_header"), 1024);
                putText(p, "supports_usb_power_delivery", new File(partner, "supports_usb_power_delivery"), 256);
                putText(p, "type", new File(partner, "type"), 256);
                if (!p.isEmpty()) m.put("partner", p);
            }
            File[] alt = d.listFiles();
            if (alt != null) {
                List<String> modes = new ArrayList<>();
                for (File f : alt) {
                    String nm = f.getName();
                    if (nm.startsWith("svid") || nm.startsWith("mode") || nm.contains("alternate")) {
                        if (modes.size() < 24) modes.add(nm);
                    }
                }
                if (!modes.isEmpty()) m.put("alternateModes", modes);
            }
            ports.add(m);
        }
        out.put("ports", ports);
        List<Object> extcon = new ArrayList<>();
        File ec = new File("/sys/class/extcon");
        for (String n : listNames(ec)) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("name", n);
            putText(x, "nameFile", new File(ec, n + "/name"), 512);
            putText(x, "state", new File(ec, n + "/state"), 1024);
            extcon.add(x);
        }
        if (!extcon.isEmpty()) out.put("extcon", extcon);
        return out;
    }

    /* -------------------------------- usb ------------------------------- */

    private static List<Object> usbInfo() {
        List<Object> out = new ArrayList<>();
        File devs = new File("/sys/bus/usb/devices");
        for (String n : listNames(devs)) {
            if (out.size() >= MAX_USB) break;
            File d = new File(devs, n);
            File vid = new File(d, "idVendor");
            if (!vid.exists()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", n);
            putText(m, "idVendor", vid, 256);
            putText(m, "idProduct", new File(d, "idProduct"), 256);
            putText(m, "manufacturer", new File(d, "manufacturer"), 1024);
            putText(m, "product", new File(d, "product"), 1024);
            putText(m, "speed", new File(d, "speed"), 256);
            putText(m, "version", new File(d, "version"), 256);
            putText(m, "bInterfaceClass", new File(d, "bInterfaceClass"), 256);
            putText(m, "bAlternateMode", new File(d, "bAlternateMode"), 256);
            out.add(m);
        }
        return out;
    }

    /* ------------------------------- debug ------------------------------ */

    private static Map<String, Object> debugInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        File dri = new File("/sys/kernel/debug/dri/0");
        if (dri.isDirectory()) {
            putText(m, "driName", new File(dri, "name"), 1024);
            putText(m, "driClients", new File(dri, "clients"), 8192);
        }
        List<String> irq = new ArrayList<>();
        String interrupts = readText(new File("/proc/interrupts"), MAX_TEXT);
        if (interrupts != null) {
            for (String line : interrupts.split("\n")) {
                String low = line.toLowerCase(Locale.US);
                if (low.contains("dpu") || low.contains("mdss") || low.contains("dsi") || low.contains("dp_") || low.contains("kgsl")) {
                    if (irq.size() < 40) irq.add(line.trim());
                }
            }
        }
        if (!irq.isEmpty()) m.put("interrupts", irq);
        String dmesg = run("/system/bin/dmesg -T 2>/dev/null || dmesg -T 2>/dev/null", 2500);
        if (dmesg != null) {
            List<String> lines = new ArrayList<>();
            for (String line : dmesg.split("\n")) {
                String low = line.toLowerCase(Locale.US);
                if (low.contains("dpu") || low.contains("mdss") || low.contains("panel") || low.contains("edid")
                        || low.contains("dp_") || low.contains("displayport") || low.contains("typec")
                        || low.contains("usbpd") || low.contains("kgsl") || low.contains("adreno")
                        || low.contains("dwc3") || hasToken(low, "usb") || hasToken(low, "hdmi")) {
                    lines.add(line.length() > 400 ? line.substring(0, 400) : line);
                }
            }
            if (lines.size() > 100) lines = lines.subList(lines.size() - 100, lines.size());
            if (!lines.isEmpty()) m.put("dmesg", lines);
        }
        File[] ksl = new File("/sys/kernel/debug/kgsl/proc").listFiles();
        if (ksl != null) {
            List<String> procs = new ArrayList<>();
            for (File f : ksl) {
                if (procs.size() < 16) procs.add(f.getName());
            }
            if (!procs.isEmpty()) m.put("kgslProcs", procs);
        }
        return m;
    }

    /* ------------------------------- utility ---------------------------- */

    private static void putText(Map<String, Object> m, String key, File f, int max) {
        if (f == null) return;
        String v = trim(readText(f, max));
        if (v != null) m.put(key, v);
    }

    private static String stripQuotes(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) return s.substring(1, s.length() - 1);
        return s;
    }

    private static String trim(String s) {
        if (s == null) return null;
        s = s.replace("\u0000", "").trim();
        return s.isEmpty() ? null : s;
    }

    private static List<String> listNames(File dir) {
        List<String> out = new ArrayList<>();
        if (dir == null) return out;
        String[] names = dir.list();
        if (names == null) return out;
        Arrays.sort(names);
        for (String n : names) if (n != null && !n.isEmpty()) out.add(n);
        return out;
    }

    private static String readText(File f, int max) {
        byte[] b = readBytes(f, max);
        if (b == null) return null;
        try {
            return new String(b, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] readBytes(File f, int max) {
        if (f == null || !f.exists() || !f.isFile()) return null;
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.min(4096, max));
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                int room = max - bos.size();
                if (room <= 0) break;
                bos.write(buf, 0, Math.min(n, room));
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private static String run(String cmd, int timeoutMs) {
        java.lang.Process p = null;
        try {
            p = new ProcessBuilder("/system/bin/sh", "-c", cmd).redirectErrorStream(true).start();
            final InputStream in = p.getInputStream();
            final ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            Thread t = new Thread(() -> {
                byte[] buf = new byte[4096];
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (bos.size() < MAX_TEXT) bos.write(buf, 0, Math.min(n, MAX_TEXT - bos.size()));
                    }
                } catch (Throwable ignored) {}
            });
            t.setDaemon(true);
            t.start();
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroy();
                return null;
            }
            t.join(400);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String sha1Safe(byte[] b) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format(Locale.US, "%02x", x));
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /* -------------------------------- JSON ------------------------------ */

    static final class Json {
        static String write(Object o) {
            StringBuilder sb = new StringBuilder(16384);
            append(sb, o);
            return sb.toString();
        }

        private static void append(StringBuilder sb, Object o) {
            if (o == null) {
                sb.append("null");
            } else if (o instanceof String) {
                quote(sb, (String) o);
            } else if (o instanceof Number) {
                double d = ((Number) o).doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) sb.append('0');
                else if (o instanceof Integer || o instanceof Long) sb.append(o);
                else sb.append(d);
            } else if (o instanceof Boolean) {
                sb.append(o);
            } else if (o instanceof Map) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    quote(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    append(sb, e.getValue());
                }
                sb.append('}');
            } else if (o instanceof Iterable) {
                sb.append('[');
                boolean first = true;
                for (Object x : (Iterable<?>) o) {
                    if (!first) sb.append(',');
                    first = false;
                    append(sb, x);
                }
                sb.append(']');
            } else {
                quote(sb, String.valueOf(o));
            }
        }

        private static void quote(StringBuilder sb, String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    case '\b': sb.append("\\b"); break;
                    case '\f': sb.append("\\f"); break;
                    default:
                        if (c < 0x20) sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                        else sb.append(c);
                }
            }
            sb.append('"');
        }
    }
}
