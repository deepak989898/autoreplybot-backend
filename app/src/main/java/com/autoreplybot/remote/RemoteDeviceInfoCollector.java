package com.autoreplybot.remote;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.LocaleList;
import android.os.PowerManager;
import android.os.StatFs;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.autoreplybot.BuildConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Collects legally accessible device information. Never collects IMEI, serial, MAC, ADID, etc.
 */
public final class RemoteDeviceInfoCollector {
    public static final int SCHEMA_VERSION = 2;

    private RemoteDeviceInfoCollector() {}

    @NonNull
    public static Map<String, Object> collect(@NonNull Context context) {
        Context app = context.getApplicationContext();
        Map<String, Object> root = new HashMap<>();
        root.put("basic", basic(app));
        root.put("battery", battery(app));
        root.put("storage", storage(app));
        root.put("memory", memory(app));
        root.put("cpu", cpu());
        root.put("display", display(app));
        root.put("camera", camera(app));
        root.put("sensors", sensors(app));
        root.put("network", network(app));
        root.put("permissions", permissions(app));
        root.put("appState", appState(app));
        root.put("collectedAt", System.currentTimeMillis());
        root.put("appVersion", BuildConfig.VERSION_NAME);
        root.put("schemaVersion", SCHEMA_VERSION);
        sanitize(root);
        return root;
    }

    /** Removes any accidentally introduced restricted identifier keys. */
    static void sanitize(@NonNull Map<String, Object> root) {
        stripBanned(root);
        for (Object v : root.values()) {
            if (v instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> child = (Map<String, Object>) v;
                stripBanned(child);
            }
        }
    }

    private static void stripBanned(@NonNull Map<String, Object> map) {
        String[] banned = {
                "imei", "meid", "imsi", "serial", "serialNumber", "androidId",
                "mac", "macAddress", "advertisingId", "phoneNumber", "simSerial",
                "hardwareSerial", "ssid", "bssid"
        };
        for (String b : banned) {
            map.remove(b);
            map.remove(b.toLowerCase(Locale.US));
            map.remove(b.toUpperCase(Locale.US));
        }
    }

    @NonNull
    private static Map<String, Object> basic(@NonNull Context app) {
        Map<String, Object> m = new HashMap<>();
        RemoteControlPrefs prefs = new RemoteControlPrefs(app);
        m.put("deviceName", prefs.getDeviceDisplayName());
        m.put("manufacturer", safe(Build.MANUFACTURER));
        m.put("brand", safe(Build.BRAND));
        m.put("model", safe(Build.MODEL));
        m.put("product", safe(Build.PRODUCT));
        m.put("androidVersion", safe(Build.VERSION.RELEASE));
        m.put("sdkVersion", Build.VERSION.SDK_INT);
        m.put("buildVersion", safe(Build.DISPLAY));
        m.put("securityPatch", Build.VERSION.SDK_INT >= 23 ? safe(Build.VERSION.SECURITY_PATCH) : "Not available on this Android version");
        m.put("appVersionName", BuildConfig.VERSION_NAME);
        m.put("appVersionCode", BuildConfig.VERSION_CODE);
        Locale locale = Locale.getDefault();
        if (Build.VERSION.SDK_INT >= 24) {
            LocaleList list = LocaleList.getDefault();
            if (list != null && list.size() > 0) locale = list.get(0);
        }
        m.put("deviceLanguage", locale != null ? locale.toLanguageTag() : "und");
        m.put("timeZone", TimeZone.getDefault().getID());
        return m;
    }

    @NonNull
    private static Map<String, Object> battery(@NonNull Context app) {
        Map<String, Object> m = new HashMap<>();
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent battery = ContextCompat.registerReceiver(app, null, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        if (battery == null) {
            m.put("percentage", "Not available on this Android version");
            return m;
        }
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int pct = (level >= 0 && scale > 0) ? Math.round(level * 100f / scale) : -1;
        m.put("percentage", pct >= 0 ? pct : "Not available on this Android version");
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        m.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL);
        int plug = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        String source = "none";
        if (plug == BatteryManager.BATTERY_PLUGGED_USB) source = "usb";
        else if (plug == BatteryManager.BATTERY_PLUGGED_AC) source = "ac";
        else if (plug == BatteryManager.BATTERY_PLUGGED_WIRELESS) source = "wireless";
        m.put("chargingSource", source);
        int temp = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        m.put("temperatureC", temp != Integer.MIN_VALUE ? temp / 10f : "Not available on this Android version");
        int health = battery.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
        m.put("health", healthLabel(health));
        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        m.put("powerSaveMode", pm != null && pm.isPowerSaveMode());
        return m;
    }

    @NonNull
    private static String healthLabel(int health) {
        switch (health) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "good";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "overheat";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "dead";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "over_voltage";
            case BatteryManager.BATTERY_HEALTH_COLD: return "cold";
            default: return "unknown";
        }
    }

    @NonNull
    private static Map<String, Object> storage(@NonNull Context app) {
        Map<String, Object> m = new HashMap<>();
        try {
            File path = Environment.getDataDirectory();
            StatFs stat = new StatFs(path.getPath());
            long block = stat.getBlockSizeLong();
            long total = stat.getBlockCountLong() * block;
            long avail = stat.getAvailableBlocksLong() * block;
            m.put("totalBytes", total);
            m.put("availableBytes", avail);
            m.put("usedBytes", Math.max(0L, total - avail));
        } catch (Exception e) {
            m.put("totalBytes", "Not available on this Android version");
        }
        try {
            File cache = app.getCacheDir();
            m.put("appCacheBytes", dirSize(cache));
        } catch (Exception e) {
            m.put("appCacheBytes", "Not available on this Android version");
        }
        try {
            File files = app.getFilesDir();
            m.put("appFilesBytes", dirSize(files));
        } catch (Exception e) {
            m.put("appFilesBytes", "Not available on this Android version");
        }
        File ext = app.getExternalFilesDir(null);
        m.put("appMediaBytes", ext != null ? dirSize(ext) : 0L);
        return m;
    }

    private static long dirSize(File dir) {
        if (dir == null || !dir.exists()) return 0L;
        long sum = 0L;
        File[] files = dir.listFiles();
        if (files == null) return 0L;
        int n = 0;
        for (File f : files) {
            if (n++ > 500) break;
            if (f.isFile()) sum += f.length();
            else if (f.isDirectory()) sum += dirSize(f);
        }
        return sum;
    }

    @NonNull
    private static Map<String, Object> memory(@NonNull Context app) {
        Map<String, Object> m = new HashMap<>();
        ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            m.put("totalRamBytes", "Not available on this Android version");
            return m;
        }
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        m.put("totalRamBytes", info.totalMem);
        m.put("availableRamBytes", info.availMem);
        m.put("lowMemory", info.lowMemory);
        return m;
    }

    @NonNull
    private static Map<String, Object> cpu() {
        Map<String, Object> m = new HashMap<>();
        m.put("supportedAbis", Build.SUPPORTED_ABIS != null
                ? String.join(",", Build.SUPPORTED_ABIS) : "");
        m.put("processorCores", Runtime.getRuntime().availableProcessors());
        boolean is64 = false;
        if (Build.SUPPORTED_64_BIT_ABIS != null && Build.SUPPORTED_64_BIT_ABIS.length > 0) {
            is64 = true;
        }
        m.put("bitSupport", is64 ? "64-bit" : "32-bit");
        m.put("hardwareName", safe(Build.HARDWARE));
        return m;
    }

    @NonNull
    private static Map<String, Object> display(@NonNull Context app) {
        Map<String, Object> m = new HashMap<>();
        DisplayMetrics metrics = app.getResources().getDisplayMetrics();
        m.put("widthPx", metrics.widthPixels);
        m.put("heightPx", metrics.heightPixels);
        m.put("densityDpi", metrics.densityDpi);
        m.put("orientation", app.getResources().getConfiguration().orientation);
        float refresh = 60f;
        try {
            WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null && Build.VERSION.SDK_INT >= 30) {
                refresh = wm.getDefaultDisplay().getRefreshRate();
            } else if (wm != null) {
                refresh = wm.getDefaultDisplay().getRefreshRate();
            }
            m.put("refreshRateHz", refresh);
        } catch (Exception e) {
            m.put("refreshRateHz", "Not available on this Android version");
        }
        return m;
    }

    @NonNull
    private static Map<String, Object> camera(@NonNull Context app) {
        Map<String, Object> m = new HashMap<>();
        boolean front = false;
        boolean back = false;
        boolean torch = false;
        List<String> qualities = new ArrayList<>();
        try {
            CameraManager cm = (CameraManager) app.getSystemService(Context.CAMERA_SERVICE);
            if (cm != null) {
                for (String id : cm.getCameraIdList()) {
                    CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                    Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) front = true;
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) back = true;
                    Boolean flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (Boolean.TRUE.equals(flash)) torch = true;
                }
                qualities.add("default");
            }
        } catch (Exception ignored) {
        }
        m.put("frontCameraAvailable", front);
        m.put("backCameraAvailable", back);
        m.put("torchAvailable", torch);
        m.put("supportedQualities", qualities);
        m.put("maxZoom", "Not available on this Android version");
        return m;
    }

    @NonNull
    private static Map<String, Object> sensors(@NonNull Context app) {
        Map<String, Object> m = new HashMap<>();
        SensorManager sm = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
        m.put("accelerometer", hasSensor(sm, Sensor.TYPE_ACCELEROMETER));
        m.put("gyroscope", hasSensor(sm, Sensor.TYPE_GYROSCOPE));
        m.put("magnetometer", hasSensor(sm, Sensor.TYPE_MAGNETIC_FIELD));
        m.put("proximity", hasSensor(sm, Sensor.TYPE_PROXIMITY));
        m.put("light", hasSensor(sm, Sensor.TYPE_LIGHT));
        LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        boolean gps = false;
        try {
            gps = lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception ignored) {
        }
        m.put("gpsProviderAvailable", gps);
        return m;
    }

    private static boolean hasSensor(SensorManager sm, int type) {
        return sm != null && sm.getDefaultSensor(type) != null;
    }

    @NonNull
    private static Map<String, Object> network(@NonNull Context app) {
        Map<String, Object> m = new HashMap<>();
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        String type = "none";
        boolean metered = false;
        boolean vpn = false;
        if (cm != null) {
            Network active = cm.getActiveNetwork();
            NetworkCapabilities caps = active != null ? cm.getNetworkCapabilities(active) : null;
            if (caps != null) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) type = "wifi";
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) type = "cellular";
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) type = "ethernet";
                vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
            }
        }
        m.put("networkType", type);
        m.put("wifiOrCellular", type);
        m.put("vpnActive", vpn);
        m.put("metered", metered);
        m.put("roaming", "Not available on this Android version");
        m.put("signal", "Not available on this Android version");
        return m;
    }

    @NonNull
    private static Map<String, Object> permissions(@NonNull Context app) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("camera", status(RemotePermissionChecks.hasCamera(app)));
        m.put("microphone", status(RemotePermissionChecks.hasMicrophone(app)));
        m.put("fineLocation", status(RemotePermissionChecks.hasFineLocation(app)));
        m.put("coarseLocation", status(RemotePermissionChecks.hasCoarseLocation(app)));
        if (Build.VERSION.SDK_INT >= 29) {
            m.put("backgroundLocation", status(RemotePermissionChecks.hasBackgroundLocation(app)));
        } else {
            m.put("backgroundLocation", "n/a");
        }
        if (Build.VERSION.SDK_INT >= 33) {
            m.put("readImages", perm(app, Manifest.permission.READ_MEDIA_IMAGES));
            m.put("readVideo", perm(app, Manifest.permission.READ_MEDIA_VIDEO));
            m.put("readAudio", perm(app, Manifest.permission.READ_MEDIA_AUDIO));
            m.put("readStorage", "n/a");
        } else {
            m.put("readImages", "n/a");
            m.put("readVideo", "n/a");
            m.put("readAudio", "n/a");
            m.put("readStorage", perm(app, Manifest.permission.READ_EXTERNAL_STORAGE));
        }
        m.put("contacts", status(RemotePermissionChecks.hasContactsAccess(app)));
        m.put("sms", status(RemotePermissionChecks.hasSmsAccess(app)));
        m.put("receiveSms", perm(app, Manifest.permission.RECEIVE_SMS));
        m.put("callLog", status(RemotePermissionChecks.hasCallLogAccess(app)));
        m.put("phoneState", perm(app, Manifest.permission.READ_PHONE_STATE));
        m.put("outgoingCalls", perm(app, Manifest.permission.PROCESS_OUTGOING_CALLS));
        m.put("callRecordingReady", status(RemotePermissionChecks.hasCallRecordingReady(app)));
        m.put("notificationListener", status(RemotePermissionChecks.hasNotificationListener(app)));
        m.put("usageAccess", status(RemoteAppUsageMirror.hasUsageAccess(app)));
        m.put("folderAccess", status(RemotePermissionChecks.hasFolderAccess(app)));
        m.put("accessibility", status(RemoteAppBlockManager.isAccessibilityEnabled(app)));
        m.put("deviceAdmin", status(RemoteAppBlockManager.isDeviceAdminActive(app)));
        if (Build.VERSION.SDK_INT >= 23) {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            boolean ignored = pm != null && pm.isIgnoringBatteryOptimizations(app.getPackageName());
            m.put("batteryOptimizationIgnored", status(ignored));
        } else {
            m.put("batteryOptimizationIgnored", "n/a");
        }
        return m;
    }

    @NonNull
    private static String status(boolean granted) {
        return granted ? "granted" : "denied";
    }

    @NonNull
    private static String perm(@NonNull Context app, @NonNull String permission) {
        return ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED
                ? "granted" : "denied";
    }

    @NonNull
    private static Map<String, Object> appState(@NonNull Context app) {
        Map<String, Object> m = new HashMap<>();
        RemoteControlPrefs prefs = new RemoteControlPrefs(app);
        RemoteModulePrefs modules = new RemoteModulePrefs(app);
        m.put("remoteControlEnabled", prefs.isRemoteControlEnabled());
        m.put("locationSharingEnabled", modules.isLocationSharingEnabled());
        m.put("galleryAccessEnabled", modules.isGalleryEnabled());
        m.put("notificationMirrorEnabled", modules.isNotificationMirrorEnabled());
        m.put("messagesSharingEnabled", modules.isMessagesSharingEnabled());
        m.put("callLogsSharingEnabled", modules.isCallLogsSharingEnabled());
        m.put("contactsSharingEnabled", modules.isContactsSharingEnabled());
        m.put("fileManagerEnabled", modules.isFileManagerEnabled());
        m.put("screenMirrorEnabled", modules.isScreenMirrorEnabled());
        m.put("screenRecordEnabled", modules.isScreenRecordEnabled());
        m.put("installedAppsSharingEnabled", modules.isInstalledAppsSharingEnabled());
        m.put("appUsageSharingEnabled", modules.isAppUsageSharingEnabled());
        m.put("appControlEnabled", modules.isAppControlEnabled());
        m.put("remoteAccessibilityEnabled",
                new RemoteAccessibilityPrefs(app).isAccessibilityControlEnabled());
        m.put("fcmTokenPresent", !prefs.getFcmToken().isEmpty());
        m.put("lastSyncAt", System.currentTimeMillis());
        return m;
    }

    @NonNull
    private static String safe(String v) {
        return v != null ? v : "";
    }
}
