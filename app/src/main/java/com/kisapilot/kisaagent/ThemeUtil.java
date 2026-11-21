package com.kisapilot.kisaagent;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.appcompat.app.AppCompatDelegate;

public class ThemeUtil {
    // 저장소에서 사용할 키(Key) 정의
    private static final String PREFS_NAME = "theme_prefs";
    private static final String KEY_THEME = "prefs_theme";

    // 테마 모드 상수 정의 (가독성을 위해)
    public static final String LIGHT_MODE = "light";
    public static final String DARK_MODE = "dark";
    public static final String DEFAULT_MODE = "default"; // 시스템 설정 따름

    // 테마를 적용하는 메서드
    public static void applyTheme(String theme) {
        switch (theme) {
            case LIGHT_MODE:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case DARK_MODE:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            default: // 시스템 설정 따름
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }

    // 사용자의 선택을 저장하는 메서드
    public static void saveTheme(Context context, String theme) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_THEME, theme);
        editor.apply();
    }

    // 저장된 테마 설정을 불러오는 메서드
    public static String getSavedTheme(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        // 저장된 값이 없으면 '시스템 설정 따름'을 기본값으로 반환
        return prefs.getString(KEY_THEME, DEFAULT_MODE);
    }
}
