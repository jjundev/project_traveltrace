package com.traveltrace.app.core.net;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowNetworkCapabilities;

@RunWith(RobolectricTestRunner.class)
public class ConnectivityMonitorTest {

    private Context ctx;
    private ConnectivityManager cm;
    private ConnectivityMonitor monitor;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        monitor = new ConnectivityMonitor(ctx);
    }

    @Test
    public void aNetworkWithInternetCapabilityIsOnline() {
        Network network = cm.getActiveNetwork();
        NetworkCapabilities caps = ShadowNetworkCapabilities.newInstance();
        Shadows.shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        Shadows.shadowOf(cm).setNetworkCapabilities(network, caps);

        assertTrue(monitor.isOnline());
    }

    @Test
    public void aNetworkWithoutInternetCapabilityIsOffline() {
        Network network = cm.getActiveNetwork();
        // 비행기 모드/캡티브 포털처럼 "연결은 있는데 인터넷은 없는" 상태.
        Shadows.shadowOf(cm).setNetworkCapabilities(network, ShadowNetworkCapabilities.newInstance());

        assertFalse(monitor.isOnline());
    }

    @Test
    public void noCapabilitiesAtAllIsOfflineAndDoesNotThrow() {
        Shadows.shadowOf(cm).setNetworkCapabilities(cm.getActiveNetwork(), null);

        assertFalse("판단할 근거가 없으면 오프라인으로 본다 — 안내가 한 번 더 뜨는 쪽이 "
                + "'오프라인 지도'라고 오해하게 두는 쪽보다 안전하다", monitor.isOnline());
    }
}
