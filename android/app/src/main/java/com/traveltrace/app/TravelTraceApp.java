package com.traveltrace.app;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

import dagger.hilt.android.HiltAndroidApp;

@HiltAndroidApp
public class TravelTraceApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // v1은 라이트 테마 고정 (values-night 미제공 + 여기서 다크모드 잠금).
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
    }
}
