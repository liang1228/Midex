package com.extbridge.daemon;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;

/** Calls the user-installed OPlusAutoHR Canary 1.0 module, by Kimu and contributors. */
final class OPlusAutoHr {
    private static final File MODULE = new File("/data/adb/modules/oplusautohr.v2");
    private static final File CLI = new File(MODULE, "bin/oahctl");

    private OPlusAutoHr() {}

    static boolean isAvailable() {
        if (!CLI.isFile() || new File(MODULE, "disable").exists() || new File(MODULE, "remove").exists()) {
            return false;
        }
        Properties metadata = new Properties();
        try (FileInputStream input = new FileInputStream(new File(MODULE, "module.prop"))) {
            metadata.load(input);
            return "oplusautohr.v2".equals(metadata.getProperty("id"))
                    && "2.0.0-canary1".equals(metadata.getProperty("version"))
                    && "201000".equals(metadata.getProperty("versionCode"));
        } catch (Exception e) {
            return false;
        }
    }

    static JSONObject call(String... args) throws Exception {
        List<String> command = new ArrayList<>(Arrays.asList("/system/bin/sh", CLI.getAbsolutePath()));
        command.addAll(Arrays.asList(args));
        File output = File.createTempFile("extbridge-oah-", ".json", MODULE);
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder(command).redirectOutput(output)
                    .redirectError(new File("/dev/null")).start();
            if (!process.waitFor(8, TimeUnit.SECONDS) || process.exitValue() != 0 || output.length() > 16384) {
                throw new IllegalStateException("OPlusAutoHR response unavailable");
            }
            return new JSONObject(new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8));
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            output.delete();
        }
    }

    static String pendingPreviewToken(JSONObject result) {
        if (!result.optBoolean("ok") || !"1".equals(result.optString("hwcOk"))) return null;
        JSONObject preview = result.optJSONObject("preview");
        if (preview == null) return null;
        String token = preview.optString("token");
        return token.matches("[0-9]{1,32}") && token.equals(result.optString("seq")) ? token : null;
    }
}
