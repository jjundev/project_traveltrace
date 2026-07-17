package com.traveltrace.app.ui.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.FragmentHomeBinding;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
public class HomeRendererTest {

    private Context ctx;
    private FragmentHomeBinding binding;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ctx.setTheme(R.style.Theme_TravelTrace);
        binding = FragmentHomeBinding.inflate(LayoutInflater.from(ctx));
    }

    /** ConstraintLayout Group 이 참조 뷰에 가시성을 실제로 전파하도록 강제하는 measure/layout. */
    private void layoutRoot() {
        View root = binding.getRoot();
        root.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 1920);
    }

    @Test
    public void tripsState_showsListAndHidesEmptyBlock() {
        HomeRenderer.render(binding, ScreenFixtures.home(), card -> {});

        assertEquals(View.VISIBLE, binding.tripList.getVisibility());
        assertEquals(View.GONE, binding.emptyGroup.getVisibility());
        assertNotNull(binding.tripList.getAdapter());
        assertEquals(2, binding.tripList.getAdapter().getItemCount());
    }

    @Test
    public void emptyState_showsEmptyBlockAndHidesList() {
        HomeRenderer.render(binding, ScreenFixtures.home(), card -> {});
        layoutRoot();
        assertEquals(View.GONE, binding.emptyGroup.getVisibility());
        assertEquals(
                "trips 상태에서는 Group 이 참조하는 실제 자식도 숨겨져야 한다",
                View.GONE, binding.emptyTitle.getVisibility());

        HomeRenderer.render(binding, ScreenFixtures.homeEmpty(), card -> {});
        layoutRoot();

        assertEquals(View.GONE, binding.tripList.getVisibility());
        assertEquals(View.VISIBLE, binding.emptyGroup.getVisibility());
        assertEquals(
                "empty 상태에서는 Group 의 가시성이 참조 자식(emptyTitle)에도 전파돼야 한다",
                View.VISIBLE, binding.emptyTitle.getVisibility());
    }

    @Test
    public void ctaIsAlwaysVisibleInBothStates() {
        HomeRenderer.render(binding, ScreenFixtures.home(), card -> {});
        assertEquals(View.VISIBLE, binding.newTripButton.getVisibility());

        HomeRenderer.render(binding, ScreenFixtures.homeEmpty(), card -> {});
        assertEquals(View.VISIBLE, binding.newTripButton.getVisibility());
    }

    @Test
    public void emptyBlockUsesPrototypeCopy() {
        HomeRenderer.render(binding, ScreenFixtures.homeEmpty(), card -> {});

        TextView title = binding.emptyTitle;
        assertEquals("첫 여행을 만들어 보세요", title.getText().toString());
    }

    @Test
    public void tripsToEmptyTransition_clearsAdapterItems() {
        HomeRenderer.render(binding, ScreenFixtures.home(), card -> {});
        assertEquals(2, binding.tripList.getAdapter().getItemCount());

        HomeRenderer.render(binding, ScreenFixtures.homeEmpty(), card -> {});

        assertNotNull(binding.tripList.getAdapter());
        assertEquals(
                "empty 로 전환되면 어댑터에 이전 trips 상태의 stale row 가 남아있으면 안 된다",
                0, binding.tripList.getAdapter().getItemCount());
    }

    @Test
    public void tripCardWithNullLocationLabel_hidesLocationPill() {
        HomeUiState.TripCard noLocation = new HomeUiState.TripCard(
                "paris", "제목", "메타", null, true);
        HomeUiState state = HomeUiState.trips(Collections.singletonList(noLocation));

        HomeRenderer.render(binding, state, card -> {});
        binding.tripList.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        binding.tripList.layout(0, 0, 1080, 1920);

        View itemView = binding.tripList.getChildAt(0);
        assertNotNull(itemView);
        View locationPill = itemView.findViewById(R.id.tripLocation);
        assertEquals(
                "locationLabel 이 null 이면 위치 pill 은 GONE 이어야 한다(빈 캡슐 방지)",
                View.GONE, locationPill.getVisibility());
    }
}
