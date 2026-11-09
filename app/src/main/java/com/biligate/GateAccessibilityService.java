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
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The whole gate lives here.
 *
 * While the Bilibili home activity is in front, an accessibility overlay
 * ("shield") is placed over everything except the top strip that holds the
 * app's own search bar. The shield eats every touch, so the recommendation
 * feed can no longer be tapped or scrolled; the search bar stays reachable
 * because the shield starts below it.
 *
 * The shield is only shown for the home activity. Search result pages, video
 * pages, the dynamic feed, etc. are separate activities and are left alone.
 */
public class GateAccessibilityService extends AccessibilityService {

    public static final String ACTION_REFRESH = "com.biligate.action.REFRESH";

    private static final String TAG = "BiliGate";
    private static final long RETRY_FOCUS_MS = 15_000L;

    /** Mainland, international, HD and "play" builds of the Bilibili app. */
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

    private WindowManager windowManager;
    private View shieldView;
    private WindowManager.LayoutParams shieldParams;
    private int shieldTop = -1;

    private String foregroundPackage;
    private String foregroundClass;
    private boolean overlayWarned;
    private boolean anchorWarned;

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
            if (isInputMethod(pkg, cls)) {
                // The keyboard opening must not be mistaken for "left the app".
                return;
            }
            foregroundPackage = pkg;
            foregroundClass = cls;
            schedule(0L);
        } else if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            schedule(120L);
        } else if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // A focused, editable field means the user is typing in search.
            if (!isBiliApp(foregroundPackage)) {
                return;
            }
            try {
                AccessibilityNodeInfo source = event.getSource();
                if (source != null && source.isEditable() && source.isFocused()) {
                    hideShield();
                    schedule(RETRY_FOCUS_MS);
                }
            } catch (Exception ignored) {
                // stale node - ignore
            }
        }
    }

    @Override
    public void onInterrupt() {
        hideShield();
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
        if (!GatePrefs.get(this).isEnabled()
                || !isBiliApp(foregroundPackage)
                || !isHomeActivity(foregroundClass)) {
            hideShield();
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return; // keep whatever is currently on screen
        }
        if (!isBiliApp(text(root.getPackageName()))) {
            hideShield();
            return;
        }
        if (hasFocusedInput(root)) {
            hideShield();
            schedule(RETRY_FOCUS_MS);
            return;
        }

        int top = computeShieldTop(root);
        showShield(top);
    }

    private int computeShieldTop(AccessibilityNodeInfo root) {
        GatePrefs prefs = GatePrefs.get(this);
        int statusBar = statusBarHeight();
        int top;
        if (prefs.isAutoInset()) {
            Rect anchor = findSearchAnchor(root);
            if (anchor != null) {
                top = anchor.bottom + dp(6);
            } else {
                top = statusBar + dp(prefs.manualInsetDp());
                warnAnchorMissingOnce();
            }
        } else {
            top = statusBar + dp(prefs.manualInsetDp());
        }
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        top = Math.max(top, statusBar + dp(24));
        top = Math.min(top, screenHeight / 2);
        return top;
    }

    // ------------------------------------------------------------- the shield

    private void warnAnchorMissingOnce() {
        if (anchorWarned) {
            return;
        }
        anchorWarned = true;
        Toast.makeText(this, R.string.toast_anchor_missing, Toast.LENGTH_LONG).show();
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
                    // view already detached
                }
            }
            return;
        }
        try {
            View view = LayoutInflater.from(this).inflate(R.layout.view_shield, null);
            // Swallow every gesture below the search bar: no taps, no swipes.
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
            // cannot even query - stay silent
        }
    }

    private void hideShield() {
        View view = shieldView;
        shieldView = null;
        shieldParams = null;
        shieldTop = -1;
        if (view != null && windowManager != null) {
            try {
                windowManager.removeViewImmediate(view);
            } catch (Exception ignored) {
                // already gone
            }
        }
    }

    // --------------------------------------------------------- node sniffing

    /**
     * Finds the rectangle of Bilibili's own search bar, which must stay
     * tappable. Returns null when this Bilibili version does not expose it.
     */
    private Rect findSearchAnchor(AccessibilityNodeInfo root) {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int maxTop = screenHeight / 3;

        java.util.List<Rect> candidates = new java.util.ArrayList<>();
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

        // Prefer the topmost match; when several boxes start at the same height
        // (search pill vs. a header that also contains the tab row) prefer the
        // shorter one, so the tab row stays covered.
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
            // stale node
        }
        return false;
    }

    /** Climbs up from a small label ("搜索") to the rounded bar that contains it. */
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
            // stale node
        }
        return null;
    }

    private boolean hasFocusedInput(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        try {
            while (!queue.isEmpty() && visited < 250) {
                AccessibilityNodeInfo node = queue.poll();
                visited++;
                if (node == null) {
                    continue;
                }
                if (node.isEditable() && node.isFocused()) {
                    return true;
                }
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) {
                        queue.add(child);
                    }
                }
            }
        } catch (Exception ignored) {
            // stale tree
        }
        return false;
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
            // already unregistered
        }
        refreshReceiver = null;
    }
}
