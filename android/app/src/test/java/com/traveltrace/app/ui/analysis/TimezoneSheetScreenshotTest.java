package com.traveltrace.app.ui.analysis;

import android.app.Activity;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;

import com.github.takahirom.roborazzi.RoborazziKt;
import com.github.takahirom.roborazzi.RoborazziOptions;
import com.traveltrace.app.R;
import com.traveltrace.app.databinding.SheetTimezoneBinding;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * 타임존 확인 시트 콘텐츠를 PNG 로 캡처한다(프로토타입 육안 대조). 시트는 모달이라 화면 전체를
 * 덮지 않으므로, 콘텐츠를 390dp 폭·wrap_content 로 렌더해 잡는다.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
public class TimezoneSheetScreenshotTest {

    private static final String OUT = "build/outputs/roborazzi/";

    private Context ctx;
    private Activity activity;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ctx.setTheme(R.style.Theme_TravelTrace);
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.Theme_TravelTrace);
    }

    @Test
    public void timezoneSheet_paris() {
        SheetTimezoneBinding binding = SheetTimezoneBinding.inflate(LayoutInflater.from(activity));
        TimezoneSheetFragment.bindContent(binding, ScreenFixtures.cityLabel());

        FrameLayout host = new FrameLayout(activity);
        host.addView(binding.getRoot());
        activity.setContentView(host);

        int w = dp(390);
        host.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        host.layout(0, 0, w, host.getMeasuredHeight());

        RoborazziKt.captureRoboImage(host, OUT + "sheet_timezone.png", new RoborazziOptions());
    }

    private int dp(int value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }
}
