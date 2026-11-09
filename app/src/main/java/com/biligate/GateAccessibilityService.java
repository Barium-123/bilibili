package com.biligate;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 遮罩服务。
 *
 * 关键改动（v1.1）：不再“只要收到可疑事件就撤遮罩”，而是每次判定都去问系统
 * “现在真正处于活动状态的应用窗口是谁”。只有确认前台确实不是哔哩哔哩首页了，
 * 才撤掉遮罩。这样我们自己加的遮罩窗口、状态栏、输入法、系统弹窗等事件都不会
 * 再把遮罩误撤掉。
 */
public class GateAccessibilityService extends AccessibilityService {

    public static final String ACTION_REFRESH = "com.biligate.action.REFRESH";

    private static final String TAG = "BiliGate";
    private static final long VERIFY_DELAY_MS = 900L;
    private static final long ANCHOR_RETRY_MS = 500L;
    private static final int ANCHOR_RETRIES = 6;

    /** 大陆版、国际版、HD 版、Play 版。 */
    private static final Set<String> BILI_PACKAGES = new HashSet<>(Arrays.asList(
            "tv.danmaku.bili",
            "com.bilibili.app.in",
            "tv.danmaku.bilibilihd",
            "tv.danmaku.biliplay"));

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable evaluateTask = new Runnable() {
        @Override
        public void run() {
            evaluateNow();
        }
    };

    /** 窗口 id -> 包名，用来判断“活跃窗口”属于谁（AccessibilityWindowInfo 没有包名接口）。 */
    private final Map<Integer, String> windowPackages = new HashMap<>();

    private WindowManager windowManager;
    private View shieldView;
    private WindowManager.LayoutParams shieldParams;
    private int shieldTop = -1;

    private String foregroundPackage;
    private String foregroundClass;

    private int anchorRetries;
    private int verifyCount;
    private long shieldShownAt;
    private boolean pendingHideCheck;
    private boolean overlayWarned;
    private boolean anchorWarned;
    private String lastSeenClass;

    private BroadcastReceiver refreshReceiver;

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        registerRefreshReceiver();
        schedule(0L);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        int type = event.getEventType();

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String pkg = text(event.getPackageName());
            String cls = text(event.getClassName());
            rememberWindow(event.getWindowId(), pkg);

            if (isInputMethod(pkg, cls)) {
                // 键盘弹出/收起不代表离开了 App。
                return;
            }
            // 遮罩窗口、我们自己的弹窗都会产生窗口事件：忽略，不要因此撤遮罩。
            if (getPackageName().equals(pkg) && !isOurActivity(cls)) {
                return;
            }
            foregroundPackage = pkg;
            // 只信 Activity 的类名，避免对话框/弹窗把“首页”覆盖掉。
            if (isBiliApp(pkg) && cls != null && cls.contains("Activity")) {
                foregroundClass = cls;
            }
            schedule(0L);

        } else if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            schedule(120L);
        }
    }

    @Override
    public void onInterrupt() {
        // 不要在这里撤遮罩：部分 ROM 在窗口切换时会回调 onInterrupt。
        schedule(0L);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        hideShield();
        unregisterRefreshReceiver();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        hideShield();
        unregisterRefreshReceiver();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // -------------------------------------------------------------- the gate

    private void schedule(long delayMs) {
        handler.removeCallbacks(evaluateTask);
        handler.postDelayed(evaluateTask, delayMs);
    }

    private void evaluateNow() {
        try {
            doEvaluate();
        } catch (Exception e) {
            Log.w(TAG, "evaluate failed", e);
        }
    }

    private void doEvaluate() {
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        }
        if (!GatePrefs.get(this).isEnabled()) {
            hideShield();
            return;
        }

        // 谁在前台，以系统给出的“活跃应用窗口”为准。
        String active = activeApplicationPackage();
        if (!isBiliApp(active)) {
            // 刚盖上遮罩的一瞬间，系统窗口信息可能还没稳定：先复查一次再撤。
            boolean justShown = shieldView != null
                    && android.os.SystemClock.uptimeMillis() - shieldShownAt < 1500L;
            if (justShown && !pendingHideCheck) {
                pendingHideCheck = true;
                schedule(250L);
                return;
            }
            pendingHideCheck = false;
            hideShield();
            return;
        }
        pendingHideCheck = false;

        String seen = foregroundClass;
        if (isBiliApp(active) && seen != null && !seen.equals(lastSeenClass)) {
            lastSeenClass = seen;
            GatePrefs.get(this).setLastSeenClass(seen);
        }

        if (!isHomeActivity(foregroundClass)) {
            hideShield();
            return;
        }

        // 已经稳定显示时清零自检次数。
        if (shieldView != null) {
            verifyCount = 0;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        boolean rootIsBili = root != null && isBiliApp(text(root.getPackageName()));

        boolean wasHidden = shieldView == null;
        if (rootIsBili) {
            anchorRetries = 0;
            showShield(computeShieldTop(root));
        } else {
            // 活跃窗口可能是我们自己的遮罩层，拿不到哔哩哔哩的节点树：
            // 保持现有遮罩，必要时用默认留白先盖上，并重试几次拿节点。
            showShield(shieldView != null ? shieldTop : fallbackTop());
            if (anchorRetries < ANCHOR_RETRIES) {
                anchorRetries++;
                schedule(ANCHOR_RETRY_MS);
            }
        }

        // 刚盖上的那一次，稍后再自检一遍：万一被别的逻辑撤掉了，这里会补回来。
        // 最多补 3 次，避免和某个持续误判的逻辑互相拉扯。
        if (wasHidden && shieldView != null) {
            shieldShownAt = android.os.SystemClock.uptimeMillis();
            if (verifyCount < 3) {
                verifyCount++;
                schedule(VERIFY_DELAY_MS);
            }
        }
    }

    private String activeApplicationPackage() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                        continue;
                    }
                    if (!window.isActive()) {
                        continue;
                    }
                    String pkg = windowPackages.get(window.getId());
                    if (pkg != null) {
                        return pkg;
                    }
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root != null) {
                        CharSequence name = root.getPackageName();
                        if (name != null) {
                            return name.toString();
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot inspect windows", e);
        }
        // 系统不返回窗口列表时退回事件记录的包名。
        return foregroundPackage;
    }

    private void rememberWindow(int windowId, String pkg) {
        if (windowId < 0 || pkg == null) {
            return;
        }
        if (windowPackages.size() > 64) {
            windowPackages.clear();
        }
        windowPackages.put(windowId, pkg);
    }

    private int computeShieldTop(AccessibilityNodeInfo root) {
        GatePrefs prefs = GatePrefs.get(this);
        int statusBar = statusBarHeight();
        if (!prefs.isAutoInset()) {
            return clampTop(statusBar + dp(prefs.manualInsetDp()), statusBar);
        }
        Rect anchor = findSearchAnchor(root);
        if (anchor == null) {
            warnAnchorMissingOnce();
            return clampTop(statusBar + dp(prefs.manualInsetDp()), statusBar);
        }
        return clampTop(anchor.bottom + dp(6), statusBar);
    }

    private int fallbackTop() {
        int statusBar = statusBarHeight();
        return clampTop(statusBar + dp(GatePrefs.get(this).manualInsetDp()), statusBar);
    }

    private int clampTop(int top, int statusBar) {
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        top = Math.max(top, statusBar + dp(24));
        return Math.min(top, screenHeight / 2);
    }

    // ------------------------------------------------------------- the shield

    private void warnAnchorMissingOnce() {
        if (anchorWarned) {
            return;
        }
        anchorWarned = true;
        Toast.makeText(this, R.string.toast_anchor_missing, Toast.LENGTH_LONG).show();
    }

    /**
     * MIUI 上遮罩被系统拦住时，最可能的原因就是没给「显示悬浮窗」。
     * 只提示一次，避免刷屏。
     */
    private void warnMissingOverlayOnce() {
        if (overlayWarned) {
            return;
        }
        try {
            if (!Settings.canDrawOverlays(this)) {
                overlayWarned = true;
                Toast.makeText(this, R.string.toast_need_overlay, Toast.LENGTH_LONG).show();
            }
        } catch (Exception ignored) {
            // 查不到就算了
        }
    }

    private void showShield(int topPx) {
        if (windowManager == null) {
            return;
        }
        if (shieldView != null) {
            if (shieldParams != null && shieldTop != topPx) {
                shieldParams.y = topPx;
                shieldTop = topPx;
                try {
                    windowManager.updateViewLayout(shieldView, shieldParams);
                } catch (Exception ignored) {
                    // 已经脱离窗口
                }
            }
            return;
        }
        try {
            View view = LayoutInflater.from(this).inflate(R.layout.view_shield, null);
            // 吞掉搜索栏以下的全部手势：不能点、不能滑、不能下拉刷新。
            view.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, android.view.MotionEvent event) {
                    return true;
                }
            });

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.y = topPx;

            windowManager.addView(view, lp);
            shieldView = view;
            shieldParams = lp;
            shieldTop = topPx;
        } catch (Exception e) {
            Log.w(TAG, "cannot show shield", e);
            shieldView = null;
            shieldParams = null;
            shieldTop = -1;
            warnMissingOverlayOnce();
        }
    }

    private void hideShield() {
        View view = shieldView;
        shieldView = null;
        shieldParams = null;
        shieldTop = -1;
        anchorRetries = 0;
        pendingHideCheck = false;
        if (view != null && windowManager != null) {
            try {
                windowManager.removeViewImmediate(view);
            } catch (Exception ignored) {
                // 已经移除
            }
        }
    }

    // --------------------------------------------------------- node sniffing

    /**
     * 找到哔哩哔哩自己的搜索栏矩形：它的下方才是要遮住的区域。
     */
    private Rect findSearchAnchor(AccessibilityNodeInfo root) {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int maxTop = screenHeight / 3;

        List<Rect> candidates = new java.util.ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;

        try {
            while (!queue.isEmpty() && visited < 400) {
                AccessibilityNodeInfo node = queue.poll();
                visited++;
                if (node == null) {
                    continue;
                }
                if (node.isVisibleToUser() && looksLikeSearch(node)) {
                    Rect candidate = containerBounds(node, screenWidth, maxTop);
                    if (candidate != null && candidates.size() < 24) {
                        candidates.add(candidate);
                    }
                }
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) {
                        queue.add(child);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "anchor scan stopped", e);
        }

        // 优先最靠上的匹配；同一高度带里（搜索框 vs 连带标签栏的头部）
        // 取更矮的那个，保证标签栏仍然被盖住。
        Rect best = null;
        int band = dp(12);
        for (Rect rect : candidates) {
            if (best == null) {
                best = rect;
            } else if (rect.top > best.top + band) {
                continue;
            } else if (rect.top + band < best.top) {
                best = rect;
            } else if (rect.bottom < best.bottom) {
                best = rect;
            }
        }
        return best;
    }

    private boolean looksLikeSearch(AccessibilityNodeInfo node) {
        try {
            String id = node.getViewIdResourceName();
            if (id != null && id.toLowerCase(Locale.ROOT).contains("search")) {
                return true;
            }
            CharSequence text = node.getText();
            if (text != null && text.toString().contains("搜索")) {
                return true;
            }
            CharSequence desc = node.getContentDescription();
            if (desc != null && desc.toString().contains("搜索")) {
                return true;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                CharSequence hint = node.getHintText();
                return hint != null && hint.toString().contains("搜索");
            }
        } catch (Exception ignored) {
            // 节点已失效
        }
        return false;
    }

    /** 从「搜索」小字往上找，找到包住它的那条圆角搜索框。 */
    private Rect containerBounds(AccessibilityNodeInfo node, int screenWidth, int maxTop) {
        Rect rect = new Rect();
        AccessibilityNodeInfo current = node;
        try {
            for (int depth = 0; depth < 5 && current != null; depth++) {
                current.getBoundsInScreen(rect);
                if (rect.width() > screenWidth / 2
                        && rect.height() >= dp(24)
                        && rect.height() <= dp(110)
                        && rect.top >= 0
                        && rect.top < maxTop) {
                    return new Rect(rect);
                }
                current = current.getParent();
            }
        } catch (Exception ignored) {
            // 节点已失效
        }
        return null;
    }

    // --------------------------------------------------------------- helpers

    private boolean isHomeActivity(String cls) {
        if (cls == null || !cls.contains("MainActivity")) {
            return false;
        }
        return !cls.contains("Search")
                && !cls.contains("Login")
                && !cls.contains("Setting")
                && !cls.contains("Splash");
    }

    private boolean isBiliApp(String pkg) {
        return pkg != null && BILI_PACKAGES.contains(pkg);
    }

    private boolean isOurActivity(String cls) {
        return cls != null && cls.startsWith(getPackageName() + ".");
    }

    private boolean isInputMethod(String pkg, String cls) {
        String p = pkg == null ? "" : pkg.toLowerCase(Locale.ROOT);
        String c = cls == null ? "" : cls;
        return p.contains("inputmethod")
                || p.contains(".ime")
                || c.contains("InputMethod")
                || c.endsWith("IME");
    }

    private static String text(CharSequence value) {
        return value == null ? null : value.toString();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) {
            return getResources().getDimensionPixelSize(id);
        }
        return dp(24);
    }

    // ------------------------------------------------------------- broadcast

    private void registerRefreshReceiver() {
        if (refreshReceiver != null) {
            return;
        }
        refreshReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                hideShield();
                schedule(0L);
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_REFRESH);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(refreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(refreshReceiver, filter);
        }
    }

    private void unregisterRefreshReceiver() {
        if (refreshReceiver == null) {
            return;
        }
        try {
            unregisterReceiver(refreshReceiver);
        } catch (Exception ignored) {
            // 已经注销
        }
        refreshReceiver = null;
    }
}
