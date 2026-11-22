package com.extbridge.helper;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

final class BluetoothDiscovery {
    static final String[] PERMISSIONS = {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
    static final int CONSENT = 3;

    interface PairingPrompt {
        boolean show(String address, int variant, int key);
    }

    private final Context context;
    private final Runnable changed;
    private final Consumer<String> pairingFailed;
    private final PairingPrompt pairingPrompt;
    private final Map<String, BluetoothDevice> nearby = new LinkedHashMap<>();
    private boolean registered;
    private boolean ownsScan;
    private boolean scanning;

    private final BroadcastReceiver events = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                ownsScan = false;
                scanning = false;
            } else if (BluetoothDevice.ACTION_FOUND.equals(action) && scanning) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                if (device != null && nearby.size() < 40) nearby.put(device.getAddress(), device);
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)
                    && intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                            == BluetoothDevice.BOND_BONDING
                    && intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                            == BluetoothDevice.BOND_NONE) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                if (device != null) pairingFailed.accept(device.getAddress());
            }
            changed.run();
        }
    };

    private final BroadcastReceiver pairingRequests = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothDevice.ACTION_PAIRING_REQUEST.equals(intent.getAction()) || !isOrderedBroadcast()) return;
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            if (device != null && pairingPrompt.show(device.getAddress(),
                    intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR),
                    intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, BluetoothDevice.ERROR))) {
                abortBroadcast();
            }
        }
    };

    BluetoothDiscovery(Context context, Runnable changed, Consumer<String> pairingFailed, PairingPrompt pairingPrompt) {
        this.context = context;
        this.changed = changed;
        this.pairingFailed = pairingFailed;
        this.pairingPrompt = pairingPrompt;
    }

    boolean hasPermission() {
        for (String permission : PERMISSIONS) {
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    boolean isScanning() {
        return scanning;
    }

    boolean start() {
        if (!hasPermission()) return false;
        BluetoothAdapter adapter = adapter();
        if (adapter == null) return false;
        try {
            if (!registered) {
                IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
                filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
                filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
                filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
                filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
                context.registerReceiver(events, filter, Context.RECEIVER_EXPORTED);
                IntentFilter requests = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
                requests.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY - 1);
                try {
                    context.registerReceiver(pairingRequests, requests, Context.RECEIVER_EXPORTED);
                } catch (RuntimeException failure) {
                    context.unregisterReceiver(events);
                    throw failure;
                }
                registered = true;
            }
            if (!scanning) {
                nearby.clear();
                if (adapter.isDiscovering()) {
                    scanning = true;
                } else {
                    ownsScan = adapter.startDiscovery();
                    scanning = ownsScan;
                }
            }
            return scanning;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    boolean pair(String address) {
        BluetoothDevice device = nearby.get(address);
        if (device == null || !hasPermission()) return false;
        try {
            BluetoothAdapter adapter = adapter();
            if (adapter == null) return false;
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
            scanning = false;
            ownsScan = false;
            return device.createBond();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    void stop() {
        if (registered) {
            context.unregisterReceiver(pairingRequests);
            context.unregisterReceiver(events);
            registered = false;
        }
        if (ownsScan) {
            try {
                BluetoothAdapter adapter = adapter();
                if (adapter != null && hasPermission()) adapter.cancelDiscovery();
            } catch (RuntimeException ignored) {}
        }
        nearby.clear();
        ownsScan = false;
        scanning = false;
    }

    List<Bundle> rows(List<Bundle> fallback) {
        if (!hasPermission()) return fallback;
        BluetoothAdapter adapter = adapter();
        if (adapter == null) return fallback;
        try {
            List<Bundle> rows = new ArrayList<>();
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                Bundle row = row(device, false);
                if (fallback != null) for (Bundle previous : fallback) {
                    if (row.getString("id").equals(previous.getString("id"))
                            || row.getString("name").equals(previous.getString("name"))) {
                        row.putBoolean("connected", previous.getBoolean("connected"));
                        row.putInt("battery", previous.getInt("battery", -1));
                        break;
                    }
                }
                rows.add(row);
            }
            rows.sort((left, right) -> Boolean.compare(right.getBoolean("connected"), left.getBoolean("connected")));
            for (BluetoothDevice device : nearby.values()) {
                if (device.getBondState() != BluetoothDevice.BOND_BONDED) rows.add(row(device, true));
            }
            return rows;
        } catch (RuntimeException failure) {
            return fallback;
        }
    }

    private Bundle row(BluetoothDevice device, boolean discovered) {
        Bundle row = new Bundle();
        String name = device.getAlias();
        if (name == null || name.isBlank()) name = device.getName();
        row.putString("name", name == null || name.isBlank() ? "未命名蓝牙设备" : name);
        row.putString("id", device.getAddress());
        row.putBoolean("nearby", discovered);
        row.putBoolean("bonded", !discovered);
        return row;
    }

    private BluetoothAdapter adapter() {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        return manager == null ? null : manager.getAdapter();
    }
}
