package com.extbridge.daemon;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;

final class QuickConnections {
    private static final Pattern WIFI_ROW = Pattern.compile(
            "^\\s*(?:[\\da-fA-F]{2}:){5}[\\da-fA-F]{2}\\s+\\d+\\s+(-?\\d+)\\s+\\S+\\s+(.*?)(?:\\s+(\\[[^\\s]*\\]))?\\s*$");
    private static final Pattern BONDED_ROW = Pattern.compile(
            "^\\s*(?:[\\da-fA-FX]{2}:){5}[\\da-fA-FX]{2}\\(.*\\[ACL BR/EDR:([YN]) LE:([YN])\\].*\\]\\s+(.+)$");

    static List<Bundle> wifi(Context context, String current, String shellScan) {
        WifiManager manager = context.getSystemService(WifiManager.class);
        if (manager == null || !manager.isWifiEnabled()) return new ArrayList<>();
        Map<String, Bundle> networks = new HashMap<>();
        String activeName = current;
        int activeLevel = -127;
        try {
            WifiInfo info = manager.getConnectionInfo();
            if (info != null && info.getNetworkId() >= 0) {
                String actual = unquote(info.getSSID());
                if (!actual.isEmpty()) activeName = actual;
                activeLevel = info.getRssi();
            }
        } catch (RuntimeException ignored) {}
        if (shellScan == null) {
            for (ScanResult scan : manager.getScanResults()) {
                addWifi(networks, scan.SSID, scan.level, scan.capabilities);
            }
        } else {
            for (String line : shellScan.split("\n")) {
                Matcher row = WIFI_ROW.matcher(line);
                if (row.matches() && row.group(3) != null) {
                    addWifi(networks, row.group(2).trim(), Integer.parseInt(row.group(1)), row.group(3));
                }
            }
        }
        if (!activeName.isEmpty()) {
            Bundle active = networks.get(activeName);
            if (active == null) {
                active = item(activeName, false, "", -1);
                active.putInt("rssi", activeLevel);
                networks.put(activeName, active);
            }
            active.putBoolean("connected", true);
        }
        try {
            List<WifiConfiguration> configs = manager.getConfiguredNetworks();
            if (configs != null) for (WifiConfiguration config : configs) {
                Bundle entry = networks.get(unquote(config.SSID));
                if (entry != null && config.networkId >= 0) {
                    entry.putString("id", String.valueOf(config.networkId));
                }
            }
        } catch (RuntimeException ignored) {}
        ArrayList<Bundle> results = new ArrayList<>(networks.values());
        results.sort(Comparator.comparing((Bundle row) -> !row.getBoolean("connected"))
                .thenComparingInt(row -> -row.getInt("rssi"))
                .thenComparing(row -> row.getString("name"), String.CASE_INSENSITIVE_ORDER));
        return results.subList(0, Math.min(40, results.size()));
    }

    private static void addWifi(Map<String, Bundle> networks, String name, int level, String capabilities) {
        if (name == null || name.isEmpty() || WifiManager.UNKNOWN_SSID.equals(name)) return;
        Bundle existing = networks.get(name);
        if (existing != null && existing.getInt("rssi") >= level) return;
        Bundle entry = item(name, false, "", -1);
        entry.putInt("rssi", level);
        String security = wifiSecurity(capabilities);
        entry.putString("security", security);
        entry.putBoolean("secured", !"open".equals(security));
        networks.put(name, entry);
    }

    static String wifiSecurity(String capabilities) {
        if (capabilities == null) return "unsupported";
        if (capabilities.contains("EAP") || capabilities.contains("WEP") || capabilities.contains("WAPI")) {
            return "unsupported";
        }
        if (capabilities.contains("SAE")) return "sae";
        if (capabilities.contains("PSK")) return "psk";
        if (capabilities.contains("OWE")) return "owe";
        return capabilities.contains("RSN") || capabilities.contains("WPA") ? "unsupported" : "open";
    }

    static List<Bundle> bluetooth(Context context, String activeName) {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        ArrayList<Bundle> results = new ArrayList<>();
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) return results;
        for (BluetoothDevice device : adapter.getBondedDevices()) {
            String name = device.getAlias();
            if (name == null || name.isEmpty()) name = device.getName();
            if (name == null || name.isEmpty()) name = "未命名蓝牙设备";
            Object connectedState = callDevice(device, "isConnected");
            boolean connected = connectedState instanceof Boolean
                    ? (Boolean) connectedState : name.equals(activeName);
            Object battery = connected ? callDevice(device, "getBatteryLevel") : null;
            results.add(item(name, connected, device.getAddress(), battery instanceof Integer ? (Integer) battery : -1));
        }
        results.sort(Comparator.comparing((Bundle row) -> !row.getBoolean("connected"))
                .thenComparing(row -> row.getString("name"), String.CASE_INSENSITIVE_ORDER));
        return results.subList(0, Math.min(40, results.size()));
    }

    static List<Bundle> bluetoothDump(String dump) {
        List<Bundle> results = new ArrayList<>();
        boolean bonded = false;
        for (String line : dump.split("\n")) {
            if (line.trim().startsWith("Bonded devices:")) {
                bonded = true;
                continue;
            }
            if (!bonded) continue;
            if (line.isBlank() || !line.startsWith("    ")) break;
            Matcher row = BONDED_ROW.matcher(line);
            if (row.matches()) {
                boolean connected = "Y".equals(row.group(1)) || "Y".equals(row.group(2));
                results.add(item(row.group(3).trim(), connected, "", -1));
                if (results.size() == 40) break;
            }
        }
        results.sort(Comparator.comparing((Bundle row) -> !row.getBoolean("connected"))
                .thenComparing(row -> row.getString("name"), String.CASE_INSENSITIVE_ORDER));
        return results;
    }

    static boolean wifiAction(Context context, String id, boolean connect) throws ReflectiveOperationException {
        WifiManager manager = context.getSystemService(WifiManager.class);
        if (manager == null || !manager.isWifiEnabled()) return false;
        if (!connect) return manager.disconnect();
        int networkId = Integer.parseInt(id);
        List<WifiConfiguration> configs = manager.getConfiguredNetworks();
        if (configs == null || configs.stream().noneMatch(config -> config.networkId == networkId)) return false;
        Class<?> listener = Class.forName("android.net.wifi.WifiManager$ActionListener");
        WifiManager.class.getMethod("connect", int.class, listener).invoke(manager, networkId, null);
        return true;
    }

    static boolean joinWifi(Context context, String ssid, String security, String password)
            throws ReflectiveOperationException {
        if (ssid == null || ssid.isEmpty() || ssid.getBytes(StandardCharsets.UTF_8).length > 32
                || password == null) return false;
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = quote(ssid);
        String securityField;
        switch (security == null ? "" : security) {
            case "open":
                securityField = "SECURITY_TYPE_OPEN";
                break;
            case "owe":
                securityField = "SECURITY_TYPE_OWE";
                break;
            case "psk":
                securityField = "SECURITY_TYPE_PSK";
                if (password.matches("[0-9a-fA-F]{64}")) {
                    config.preSharedKey = password;
                } else {
                    int length = password.getBytes(StandardCharsets.UTF_8).length;
                    if (length < 8 || length > 63) return false;
                    config.preSharedKey = quote(password);
                }
                break;
            case "sae":
                securityField = "SECURITY_TYPE_SAE";
                if (password.isEmpty() || password.getBytes(StandardCharsets.UTF_8).length > 63) return false;
                config.preSharedKey = quote(password);
                break;
            default:
                return false;
        }
        WifiManager manager = context.getSystemService(WifiManager.class);
        if (manager == null || !manager.isWifiEnabled()) return false;
        int securityType = WifiConfiguration.class.getField(securityField).getInt(null);
        WifiConfiguration.class.getMethod("setSecurityParams", int.class).invoke(config, securityType);
        Class<?> listener = Class.forName("android.net.wifi.WifiManager$ActionListener");
        WifiManager.class.getMethod("connect", WifiConfiguration.class, listener).invoke(manager, config, null);
        return true;
    }

    private static String quote(String value) {
        return "\"" + value + "\"";
    }

    static boolean bluetoothAction(Context context, String address, boolean connect) {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled() || !BluetoothAdapter.checkBluetoothAddress(address)) return false;
        for (BluetoothDevice device : adapter.getBondedDevices()) {
            if (device.getAddress().equals(address)) {
                Object result = callDevice(device, connect ? "connect" : "disconnect");
                return Boolean.TRUE.equals(result) || Integer.valueOf(0).equals(result);
            }
        }
        return false;
    }

    static boolean answerBluetoothPairing(Context context, String address, boolean accept, String pin) {
        if (address == null || !BluetoothAdapter.checkBluetoothAddress(address)) return false;
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            android.util.Log.w("ExtBridge", "Bluetooth pairing reply has no adapter");
            return false;
        }
        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            int bondState = device.getBondState();
            if (bondState != BluetoothDevice.BOND_BONDING) {
                android.util.Log.w("ExtBridge", "Bluetooth pairing reply ignored in bond state " + bondState);
                return false;
            }
            Object owner = BluetoothDevice.class.getMethod("getPackageNameOfBondingApplication").invoke(device);
            if (!"com.extbridge.helper".equals(owner)) {
                android.util.Log.w("ExtBridge", "Bluetooth pairing reply has no matching owner");
                return false;
            }
            if (!accept) return Boolean.TRUE.equals(callDevice(device, "cancelBondProcess"));
            if (pin == null) {
                return Boolean.TRUE.equals(BluetoothDevice.class.getMethod("setPairingConfirmation", boolean.class)
                        .invoke(device, true));
            }
            byte[] bytes = pin.getBytes(StandardCharsets.UTF_8);
            return bytes.length >= 1 && bytes.length <= 16
                    && Boolean.TRUE.equals(BluetoothDevice.class.getMethod("setPin", byte[].class)
                            .invoke(device, (Object) bytes));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            android.util.Log.w("ExtBridge", "Bluetooth pairing reply failed", failure);
            return false;
        }
    }

    private static Object callDevice(BluetoothDevice device, String method) {
        try {
            return BluetoothDevice.class.getMethod(method).invoke(device);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Bundle item(String name, boolean connected, String id, int battery) {
        Bundle entry = new Bundle();
        entry.putString("name", name);
        entry.putBoolean("connected", connected);
        entry.putString("id", id);
        entry.putInt("battery", battery);
        return entry;
    }

    private static String unquote(String name) {
        if (name == null || WifiManager.UNKNOWN_SSID.equals(name)) return "";
        return name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")
                ? name.substring(1, name.length() - 1) : name;
    }
}
