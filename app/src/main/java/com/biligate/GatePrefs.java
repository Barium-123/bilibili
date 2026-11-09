package com.biligate;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Local, offline storage for the master switch and the PIN.
 *
 * The PIN itself is never stored: only salt + SHA-256(salt || pin).
 * There is no networking code anywhere in the app, so this data cannot leave
 * the device.
 */
public final class GatePrefs {

    public static final int PIN_OK = 0;
    public static final int PIN_WRONG = 1;
    public static final int PIN_LOCKED = 2;

    private static final String FILE = "biligate_prefs";
    private static final String K_ENABLED = "enabled";
    private static final String K_SALT = "pin_salt";
    private static final String K_HASH = "pin_hash";
    private static final String K_FAILS = "fails";
    private static final String K_LOCK_UNTIL = "lock_until";
    private static final String K_AUTO_INSET = "auto_inset";
    private static final String K_MANUAL_INSET_DP = "manual_inset_dp";

    private static final int MAX_FAILS = 5;
    private static final long LOCK_MS = 30_000L;

    private final SharedPreferences sp;

    private GatePrefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static GatePrefs get(Context context) {
        return new GatePrefs(context);
    }

    // ---------------------------------------------------------------- switch

    public boolean isEnabled() {
        return sp.getBoolean(K_ENABLED, false);
    }

    public void setEnabled(boolean enabled) {
        sp.edit().putBoolean(K_ENABLED, enabled).apply();
    }

    // ------------------------------------------------------------------- pin

    public boolean isPinSet() {
        return sp.contains(K_SALT) && sp.contains(K_HASH);
    }

    public void setPin(String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        sp.edit()
                .putString(K_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(K_HASH, hash(salt, pin))
                .putInt(K_FAILS, 0)
                .putLong(K_LOCK_UNTIL, 0L)
                .apply();
    }

    public int verifyPin(String pin) {
        if (!isPinSet()) {
            return PIN_OK;
        }
        long now = System.currentTimeMillis();
        if (sp.getLong(K_LOCK_UNTIL, 0L) > now) {
            return PIN_LOCKED;
        }

        byte[] salt = new byte[0];
        try {
            salt = Base64.decode(sp.getString(K_SALT, ""), Base64.NO_WRAP);
        } catch (IllegalArgumentException ignored) {
            // corrupted salt: fall through, the hash comparison will fail
        }
        String expected = sp.getString(K_HASH, "");
        if (expected.length() > 0 && expected.equals(hash(salt, pin))) {
            sp.edit().putInt(K_FAILS, 0).putLong(K_LOCK_UNTIL, 0L).apply();
            return PIN_OK;
        }

        int fails = sp.getInt(K_FAILS, 0) + 1;
        if (fails >= MAX_FAILS) {
            sp.edit().putInt(K_FAILS, 0).putLong(K_LOCK_UNTIL, now + LOCK_MS).apply();
            return PIN_LOCKED;
        }
        sp.edit().putInt(K_FAILS, fails).apply();
        return PIN_WRONG;
    }

    public int remainingAttempts() {
        return Math.max(0, MAX_FAILS - sp.getInt(K_FAILS, 0));
    }

    public long lockRemainingMs() {
        return Math.max(0L, sp.getLong(K_LOCK_UNTIL, 0L) - System.currentTimeMillis());
    }

    // ---------------------------------------------------------- shield inset

    /** true = look up the search bar bounds at runtime, false = use the manual dp value. */
    public boolean isAutoInset() {
        return sp.getBoolean(K_AUTO_INSET, true);
    }

    public void setAutoInset(boolean auto) {
        sp.edit().putBoolean(K_AUTO_INSET, auto).apply();
    }

    public int manualInsetDp() {
        return sp.getInt(K_MANUAL_INSET_DP, 64);
    }

    public void setManualInsetDp(int dp) {
        sp.edit().putInt(K_MANUAL_INSET_DP, Math.max(0, Math.min(200, dp))).apply();
    }

    // ---------------------------------------------------------------- crypto

    private static String hash(byte[] salt, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            byte[] out = md.digest(pin.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
