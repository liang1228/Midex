package com.extbridge.daemon;

import org.json.JSONObject;

public final class OPlusAutoHrTest {
    public static void main(String[] args) throws Exception {
        check("{\"ok\":true,\"hwcOk\":\"1\",\"seq\":\"123\",\"preview\":{\"token\":\"123\"}}", "123");
        check("{\"ok\":true,\"hwcOk\":\"0\",\"seq\":\"123\",\"preview\":{\"token\":\"123\"}}", null);
        check("{\"ok\":true,\"hwcOk\":\"pending\",\"preview\":null}", null);
        check("{\"ok\":true,\"hwcOk\":\"1\",\"seq\":\"123\",\"preview\":null}", null);
        check("{\"ok\":true,\"hwcOk\":\"1\",\"seq\":\"123\",\"preview\":{\"token\":\"124\"}}", null);
        check("{\"ok\":true,\"hwcOk\":\"1\",\"seq\":\"../123\",\"preview\":{\"token\":\"../123\"}}", null);
        System.out.println("PASS: 6 OPlusAutoHR preview receipt cases");
    }

    private static void check(String json, String expected) throws Exception {
        String token = OPlusAutoHr.pendingPreviewToken(new JSONObject(json));
        if (!java.util.Objects.equals(expected, token)) throw new AssertionError(json);
    }
}
