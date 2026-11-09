package com.biligate;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * 小米 / 红米 / POCO（MIUI、HyperOS）上，无障碍遮罩和后台存活都要额外的系统授权：
 *
 * 1. 显示悬浮窗：MIUI 对 TYPE_ACCESSIBILITY_OVERLAY 也按“悬浮窗”管理，
 *    没有这个权限时 addView 可能直接失败，遮罩不出现。
 * 2. 自启动：重启后让无障碍服务能被系统重新拉起。
 * 3. 省电策略：改成“无限制”，避免后台被清理后拦截悄悄失效。
 */
public final class MiuiUtils {

    private MiuiUtils() {
    }

    public static boolean isMiui() {
        String brand = Build.BRAND == null ? "" : Build.BRAND.toLowerCase();
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase();
        return brand.contains("xiaomi")
                || brand.contains("redmi")
                || brand.contains("poco")
                || manufacturer.contains("xiaomi");
    }

    public static boolean canDrawOverlays(Context context) {
        return Settings.canDrawOverlays(context);
    }

    public static boolean openOverlaySettings(Context context) {
        Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return tryStart(context, intent);
    }

    public static boolean isIgnoringBatteryOptimizations(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /**
     * 优先打开 MIUI 的“省电策略”页面；打不开时退回系统电池优化设置。
     */
    public static boolean openBatterySettings(Context context) {
        Intent miui = new Intent();
        miui.setComponent(new ComponentName(
                "com.miui.powerkeeper",
                "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"));
        miui.putExtra("package_name", context.getPackageName());
        miui.putExtra("package_label", context.getString(R.string.app_name));
        miui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (tryStart(context, miui)) {
            return true;
        }

        Intent request = new Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + context.getPackageName()));
        request.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (tryStart(context, request)) {
            return true;
        }

        Intent list = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        list.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return tryStart(context, list);
    }

    /** 打开 MIUI 的“自启动管理”；HyperOS 上组件名相同，找不到就退回应用详情页。 */
    public static boolean openAutoStartSettings(Context context) {
        Intent miui = new Intent();
        miui.setComponent(new ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"));
        miui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (tryStart(context, miui)) {
            return true;
        }
        return openAppDetails(context);
    }

    public static boolean openAppDetails(Context context) {
        Intent intent = new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return tryStart(context, intent);
    }

    private static boolean tryStart(Context context, Intent intent) {
        try {
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
