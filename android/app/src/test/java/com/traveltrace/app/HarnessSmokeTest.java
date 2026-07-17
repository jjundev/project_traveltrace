package com.traveltrace.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.view.LayoutInflater;

import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.databinding.FragmentPhotoSelectionBinding;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 하네스 스모크: Robolectric 이 앱 테마로 레이아웃을 인플레이트하고
 * 색 리소스를 해석할 수 있는지 확인한다. 화면 Renderer 테스트의 전제.
 *
 * <p>일부러 map 레이아웃을 쓰지 않는다 — Task 7 이후 그 레이아웃은 SupportMapFragment 를
 * 담는 FragmentContainerView 를 갖게 되고, FragmentContainerView 는 FragmentManager 밖에서
 * 인플레이트하면 예외를 던진다.
 */
@RunWith(RobolectricTestRunner.class)
public class HarnessSmokeTest {

    @Test
    public void inflatesLayoutWithAppTheme() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ctx.setTheme(R.style.Theme_TravelTrace);

        FragmentPhotoSelectionBinding binding =
                FragmentPhotoSelectionBinding.inflate(LayoutInflater.from(ctx));

        assertNotNull(binding.getRoot());
    }

    @Test
    public void resolvesSemanticColorTokens() {
        Context ctx = ApplicationProvider.getApplicationContext();
        // colors.xml 의 fill_brand → palette_blue_500 → #3182F6
        assertEquals(0xFF3182F6, ctx.getColor(R.color.fill_brand));
    }
}
