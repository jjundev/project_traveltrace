package com.traveltrace.app.ui.map;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.R;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class TimelineScrubberViewTest {

    private static final int WIDTH = 360;
    private static final int HEIGHT = 24;

    private TimelineScrubberView view;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ctx.setTheme(R.style.Theme_TravelTrace);
        view = new TimelineScrubberView(ctx);
        view.setStops(ScreenFixtures.map().stops);
        view.measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, WIDTH, HEIGHT);
    }

    @Test
    public void activeIndexDefaultsToZero() {
        assertEquals(0, view.getActiveIndex());
    }

    @Test
    public void setActiveIndexIsClampedToStopRange() {
        view.setActiveIndex(99);
        assertEquals(5, view.getActiveIndex());

        view.setActiveIndex(-3);
        assertEquals(0, view.getActiveIndex());
    }

    @Test
    public void indexAtMapsLeftEdgeToFirstStopAndRightEdgeToLast() {
        assertEquals(0, view.indexAt(0f));
        assertEquals(5, view.indexAt(WIDTH));
    }

    @Test
    public void indexAtMapsMiddleToMiddleStop() {
        // 6개 정차점을 6등분 → 중앙(180px)은 index 3 슬롯의 시작
        assertEquals(3, view.indexAt(WIDTH / 2f));
    }

    @Test
    public void indexAtReturnsNoSelectionWhenEmpty() {
        TimelineScrubberView empty =
                new TimelineScrubberView(ApplicationProvider.getApplicationContext());
        assertEquals(-1, empty.indexAt(10f));
    }

    @Test
    public void tapNotifiesListenerWithStopIndex() {
        final int[] notified = {-1};
        view.setOnStopSelectedListener(index -> notified[0] = index);

        view.performTapAt(WIDTH);

        assertEquals(5, notified[0]);
        assertEquals("탭은 활성 인덱스도 옮긴다", 5, view.getActiveIndex());
    }
}
