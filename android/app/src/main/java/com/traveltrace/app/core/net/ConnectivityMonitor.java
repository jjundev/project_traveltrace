package com.traveltrace.app.core.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * "지금 지도 타일을 받아올 수 있는가"를 판정한다 (PRD §5 오프라인).
 *
 * <p>저장된 여행의 <em>재생</em>은 네트워크가 없어도 완결된다 — 좌표·시각·이름이 전부 Room 에
 * 있기 때문이다. 하지만 <b>지도 타일은 2D·3D 모두 네트워크가 필요하다.</b> 그래서 이 판정의
 * 용도는 재생을 막는 것이 아니라 <em>배경이 비는 이유를 설명하는 것</em>뿐이다.
 *
 * <p>클래스와 {@link #isOnline()} 은 일부러 final 이 아니다 — MAP ViewModel 테스트가
 * ConnectivityManager 섀도를 세우는 대신 상속으로 온/오프라인을 고정할 수 있게 한다.
 */
@Singleton
public class ConnectivityMonitor {

    private final Context context;

    @Inject
    public ConnectivityMonitor(@ApplicationContext Context context) {
        this.context = context;
    }

    /** 판단 근거가 없으면 false — 안내가 한 번 더 뜨는 쪽이 오해를 남기는 쪽보다 안전하다. */
    public boolean isOnline() {
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        Network active = cm.getActiveNetwork();
        if (active == null) return false;

        NetworkCapabilities caps = cm.getNetworkCapabilities(active);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
}
