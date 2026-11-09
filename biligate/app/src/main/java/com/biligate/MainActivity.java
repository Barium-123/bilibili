package com.biligate;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 设置页：无障碍状态、需要密码的总开关、小米系统授权、遮罩位置微调。
 */
public class MainActivity extends Activity {

    private GatePrefs prefs;
    private boolean suppress;

    private Switch swEnabled;
    private CheckBox cbAuto;
    private SeekBar seekInset;
    private TextView tvInset;
    private TextView tvServiceStatus;
    private TextView tvMasterHint;
    private TextView tvDeviceTag;
    private TextView tvDeviceHint;
    private TextView tvOverlayState;
    private TextView tvAutoStartState;
    private TextView tvBatteryState;
    private Button btnOpenService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = GatePrefs.get(this);

        swEnabled = findViewById(R.id.swEnabled);
        cbAuto = findViewById(R.id.cbAuto);
        seekInset = findViewById(R.id.seekInset);
        tvInset = findViewById(R.id.tvInset);
        tvServiceStatus = findViewById(R.id.tvServiceStatus);
        tvMasterHint = findViewById(R.id.tvMasterHint);
        tvDeviceTag = findViewById(R.id.tvDeviceTag);
        tvDeviceHint = findViewById(R.id.tvDeviceHint);
        tvOverlayState = findViewById(R.id.tvOverlayState);
        tvAutoStartState = findViewById(R.id.tvAutoStartState);
        tvBatteryState = findViewById(R.id.tvBatteryState);
        btnOpenService = findViewById(R.id.btnOpenService);

        btnOpenService.setOnClickListener(v -> openAccessibilitySettings());
        findViewById(R.id.btnChangePin).setOnClickListener(v -> changePin());
        findViewById(R.id.btnHelp).setOnClickListener(v -> showHelp());
        findViewById(R.id.btnForgot).setOnClickListener(v -> showForgotPin());

        findViewById(R.id.rowOverlay).setOnClickListener(v -> {
            if (!MiuiUtils.openOverlaySettings(this)) {
                toast(getString(R.string.toast_open_failed));
            }
        });
        findViewById(R.id.rowAutoStart).setOnClickListener(v -> {
            if (!MiuiUtils.openAutoStartSettings(this)) {
                toast(getString(R.string.toast_open_failed));
            }
        });
        findViewById(R.id.rowBattery).setOnClickListener(v -> {
            if (!MiuiUtils.openBatterySettings(this)) {
                toast(getString(R.string.toast_open_failed));
            }
        });

        swEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppress) {
                return;
            }
            if (isChecked) {
                if (!prefs.isPinSet()) {
                    createPin(() -> applyEnabled(true));
                } else {
                    askPin(R.string.ask_pin_enable, () -> applyEnabled(true));
                }
            } else {
                askPin(R.string.ask_pin_disable, () -> applyEnabled(false));
            }
        });

        cbAuto.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppress) {
                return;
            }
            prefs.setAutoInset(isChecked);
            seekInset.setEnabled(!isChecked);
            notifyService();
        });

        seekInset.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (!fromUser || suppress) {
                    return;
                }
                prefs.setManualInsetDp(value);
                tvInset.setText(getString(R.string.inset_value, value));
                notifyService();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    // ------------------------------------------------------------------ 状态

    private void refreshUi() {
        boolean service = isAccessibilityServiceEnabled();
        boolean enabled = prefs.isEnabled();

        tvServiceStatus.setText(service ? R.string.service_on : R.string.service_off);
        tvServiceStatus.setTextColor(getColor(service ? R.color.ok : R.color.warn));
        btnOpenService.setVisibility(service ? View.GONE : View.VISIBLE);
        tvMasterHint.setText(enabled ? R.string.master_on : R.string.master_off);

        boolean miui = MiuiUtils.isMiui();
        tvDeviceTag.setText(miui ? R.string.device_tag_miui : R.string.device_tag_other);
        tvDeviceHint.setText(miui ? R.string.device_hint_miui : R.string.device_hint_other);

        boolean overlay = MiuiUtils.canDrawOverlays(this);
        tvOverlayState.setText(overlay ? R.string.state_granted : R.string.state_missing);
        tvOverlayState.setTextColor(getColor(overlay ? R.color.ok : R.color.warn));

        boolean battery = MiuiUtils.isIgnoringBatteryOptimizations(this);
        tvBatteryState.setText(battery ? R.string.state_battery_ok : R.string.state_battery_limited);
        tvBatteryState.setTextColor(getColor(battery ? R.color.ok : R.color.warn));

        tvAutoStartState.setText(R.string.state_recommend);
        tvAutoStartState.setTextColor(getColor(R.color.text3));

        suppress = true;
        swEnabled.setChecked(enabled);
        cbAuto.setChecked(prefs.isAutoInset());
        seekInset.setEnabled(!prefs.isAutoInset());
        seekInset.setProgress(prefs.manualInsetDp());
        tvInset.setText(getString(R.string.inset_value, prefs.manualInsetDp()));
        suppress = false;
    }

    private boolean isAccessibilityServiceEnabled() {
        AccessibilityManager manager =
                (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (manager == null) {
            return false;
        }
        List<AccessibilityServiceInfo> services =
                manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo info : services) {
            if (info.getResolveInfo() == null || info.getResolveInfo().serviceInfo == null) {
                continue;
            }
            if (getPackageName().equals(info.getResolveInfo().serviceInfo.packageName)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 开关

    private void applyEnabled(boolean enabled) {
        prefs.setEnabled(enabled);
        refreshUi();
        notifyService();
        toast(getString(enabled ? R.string.toast_enabled : R.string.toast_disabled));

        if (!enabled) {
            return;
        }
        if (!isAccessibilityServiceEnabled()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.need_service_title)
                    .setMessage(R.string.need_service_text)
                    .setPositiveButton(R.string.btn_open_settings, (d, w) -> openAccessibilitySettings())
                    .setNegativeButton(R.string.btn_later, null)
                    .show();
            return;
        }
        if (MiuiUtils.isMiui() && !MiuiUtils.canDrawOverlays(this)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.need_overlay_title)
                    .setMessage(R.string.need_overlay_text)
                    .setPositiveButton(R.string.btn_open_settings,
                            (d, w) -> MiuiUtils.openOverlaySettings(this))
                    .setNegativeButton(R.string.btn_later, null)
                    .show();
        }
    }

    private void notifyService() {
        sendBroadcast(new Intent(GateAccessibilityService.ACTION_REFRESH)
                .setPackage(getPackageName()));
    }

    // ------------------------------------------------------------------ 密码

    private void askPin(int titleRes, Runnable onOk) {
        LinearLayout box = dialogBox();
        EditText input = pinField(getString(R.string.pin_current));
        box.addView(input, dialogParams());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setView(box)
                .setPositiveButton(R.string.confirm, null)
                .setNegativeButton(R.string.cancel, null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    int result = prefs.verifyPin(input.getText().toString().trim());
                    if (result == GatePrefs.PIN_OK) {
                        dialog.dismiss();
                        onOk.run();
                    } else if (result == GatePrefs.PIN_LOCKED) {
                        long seconds = Math.max(1L, prefs.lockRemainingMs() / 1000L);
                        toast(getString(R.string.pin_locked, (int) seconds));
                    } else {
                        input.setText("");
                        input.setError(getString(R.string.pin_wrong, prefs.remainingAttempts()));
                    }
                }));
        dialog.setOnDismissListener(d -> refreshUi());
        dialog.show();
    }

    private void createPin(Runnable after) {
        LinearLayout box = dialogBox();
        EditText first = pinField(getString(R.string.pin_new));
        EditText second = pinField(getString(R.string.pin_new_again));
        box.addView(first, dialogParams());
        box.addView(second, dialogParams());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.pin_set_title)
                .setView(box)
                .setPositiveButton(R.string.confirm, null)
                .setNegativeButton(R.string.cancel, null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String a = first.getText().toString().trim();
                    String b = second.getText().toString().trim();
                    if (a.length() < 4 || a.length() > 8) {
                        first.setError(getString(R.string.pin_length));
                        return;
                    }
                    if (!a.equals(b)) {
                        second.setError(getString(R.string.pin_mismatch));
                        return;
                    }
                    prefs.setPin(a);
                    dialog.dismiss();
                    toast(getString(R.string.pin_saved));
                    if (after != null) {
                        after.run();
                    }
                }));
        dialog.setOnDismissListener(d -> refreshUi());
        dialog.show();
    }

    private void changePin() {
        if (!prefs.isPinSet()) {
            createPin(null);
            return;
        }
        askPin(R.string.ask_pin_change, () -> createPin(null));
    }

    private EditText pinField(String hint) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
        edit.setTextColor(getColor(R.color.text));
        edit.setHintTextColor(getColor(R.color.text3));
        edit.setTextSize(18f);
        return edit;
    }

    private LinearLayout dialogBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(6), pad, 0);
        return box;
    }

    private LinearLayout.LayoutParams dialogParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(6);
        return params;
    }

    // ---------------------------------------------------------------- 系统页

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            toast(getString(R.string.accessibility_path));
        } catch (Exception e) {
            toast(getString(R.string.toast_open_failed));
        }
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.help_title)
                .setMessage(R.string.help_text)
                .setPositiveButton(R.string.confirm, null)
                .show();
    }

    private void showForgotPin() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.forgot_title)
                .setMessage(R.string.forgot_text)
                .setPositiveButton(R.string.confirm, null)
                .show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
