package com.traveltrace.app.ui.home;

import android.app.Activity;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;

import com.github.takahirom.roborazzi.RoborazziKt;
import com.github.takahirom.roborazzi.RoborazziOptions;
import com.traveltrace.app.R;
import com.traveltrace.app.databinding.FragmentHomeBinding;
import com.traveltrace.app.ui.common.ToastPresenter;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * HOME 화면의 실제 렌더를 PNG 로 캡처한다(디자인 프로토타입과 육안 대조용).
 *
 * <p>Roborazzi 의 Gradle 플러그인은 Kotlin Gradle 플러그인을 요구하므로 적용하지 않는다.
 * 대신 라이브러리의 captureRoboImage() 를 직접 호출하고, 기록 여부·출력 경로는
 * build.gradle 의 시스템 프로퍼티(roborazzi.test.record / roborazzi.output.dir)로 준다.
 *
 * <p>프레임은 프로토타입 기준 390x844dp(@Config qualifiers), 그래픽스는 NATIVE 모드여야
 * 실제 픽셀이 그려진다. Renderer 는 static 이라 Fragment/Hilt 없이 직접 호출한다.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
public class HomeScreenshotTest {

    private static final String OUT = "build/outputs/roborazzi/";

    private Context ctx;
    private FragmentHomeBinding binding;
    private Activity activity;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ctx.setTheme(R.style.Theme_TravelTrace);
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.Theme_TravelTrace);
        binding = FragmentHomeBinding.inflate(LayoutInflater.from(activity));
    }

    /** root 를 액티비티에 붙이고 화면 프레임 크기로 강제 레이아웃한 뒤 캡처한다. */
    private void attachAndCapture(String fileName) {
        FrameLayout host = new FrameLayout(activity);
        host.addView(binding.getRoot());
        activity.setContentView(host);

        int w = dp(390);
        int h = dp(844);
        host.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        host.layout(0, 0, w, h);

        RoborazziKt.captureRoboImage(host, OUT + fileName, new RoborazziOptions());
    }

    private int dp(int value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }

    @Test
    public void home_withTrips() {
        HomeRenderer.render(binding, ScreenFixtures.home(), card -> {});
        attachAndCapture("home_trips.png");
    }

    @Test
    public void home_empty() {
        HomeRenderer.render(binding, ScreenFixtures.homeEmpty(), card -> {});
        attachAndCapture("home_empty.png");
    }

    @Test
    public void home_withToast() {
        HomeRenderer.render(binding, ScreenFixtures.home(), card -> {});
        ToastPresenter.show(binding.getRoot(), "데모에선 파리 여행만 열려요");
        attachAndCapture("home_toast.png");
    }
}
