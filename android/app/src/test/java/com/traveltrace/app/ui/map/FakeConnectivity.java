package com.traveltrace.app.ui.map;

import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.core.net.ConnectivityMonitor;

/**
 * MAP ViewModel 테스트용 연결 상태 고정. ConnectivityManager 섀도를 세우는 대신 상속으로
 * 답을 박는다 — 이 테스트들이 보는 건 연결 판정이 아니라 그 결과가 상태에 실리는지다
 * (판정 자체는 ConnectivityMonitorTest 가 본다).
 */
public final class FakeConnectivity {

    private FakeConnectivity() {}

    public static ConnectivityMonitor online() {
        return fixed(true);
    }

    public static ConnectivityMonitor offline() {
        return fixed(false);
    }

    private static ConnectivityMonitor fixed(boolean online) {
        return new ConnectivityMonitor(ApplicationProvider.getApplicationContext()) {
            @Override
            public boolean isOnline() {
                return online;
            }
        };
    }
}
