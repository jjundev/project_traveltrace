package com.traveltrace.app.ui.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.core.AppExecutors;
import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.core.model.LocationSource;
import com.traveltrace.app.data.db.TravelTraceDatabase;
import com.traveltrace.app.data.repo.RoomPhotoAnalysisRepository;
import com.traveltrace.app.data.repo.RoomTripRepository;
import com.traveltrace.app.domain.Callback;
import com.traveltrace.app.domain.model.PhotoAnalysis;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLooper;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@RunWith(RobolectricTestRunner.class)
public class HomeViewModelTest {

    private AppExecutors executors;
    private TravelTraceDatabase db;
    private RoomPhotoAnalysisRepository analysisRepo;
    private HomeViewModel vm;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        executors = new AppExecutors();
        db = Room.inMemoryDatabaseBuilder(ctx, TravelTraceDatabase.class)
                .allowMainThreadQueries()
                .build();
        analysisRepo = new RoomPhotoAnalysisRepository(db, executors);
        vm = new HomeViewModel(ctx, new RoomTripRepository(db, executors));
    }

    @After
    public void tearDown() {
        executors.shutdown();
        db.close();
    }

    private static PhotoAnalysis placed(long id, String name, long takenAt) {
        PhotoAnalysis a = new PhotoAnalysis();
        a.mediaStoreId = id;
        a.displayName = name;
        a.takenAtUtc = takenAt;
        a.takenAtHasOffset = true;
        a.lat = 48.85;
        a.lng = 2.29;
        a.source = LocationSource.GPS;
        a.classification = LocationClassification.PLACED;
        return a;
    }

    /**
     * RoomTripRepositoryTest.await() 와 동일한 모양. AppExecutors.io() 는 진짜 스레드풀이라
     * saveTrip 트리거 직후 idleMainLooper() 를 한 번만 부르면 커밋 전에 진행될 수 있다 —
     * 콜백이 실제로 도착할 때까지 짧게 반복해서 기다린다.
     */
    private static <T> T await(Consumer<Callback<T>> call) {
        AtomicReference<T> box = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean(false);
        call.accept(v -> {
            box.set(v);
            done.set(true);
        });
        long deadline = System.currentTimeMillis() + 5_000L;
        while (!done.get() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper();
            if (!done.get()) {
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
        }
        assertTrue("콜백이 5초 안에 와야 한다", done.get());
        return box.get();
    }

    /**
     * vm.refresh() 도 TripRepository.list() 를 거쳐 같은 io() 스레드풀을 탄다 — 단발
     * idleMainLooper() 는 레이스가 난다. state() LiveData 값이 새 객체로 바뀔 때까지
     * 데드라인을 두고 반복해서 기다린다(HomeUiState.toState() 는 매번 새 인스턴스를 만든다).
     */
    private HomeUiState refreshed() {
        HomeUiState previous = vm.state().getValue();
        vm.refresh();
        long deadline = System.currentTimeMillis() + 5_000L;
        HomeUiState current = previous;
        while (current == previous && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper();
            current = vm.state().getValue();
            if (current == previous) {
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
        }
        assertNotSame("HomeViewModel.refresh() 결과가 5초 안에 와야 한다", previous, current);
        return current;
    }

    @Test
    public void noTripsYieldsTheEmptyState() {
        HomeUiState state = refreshed();

        assertNotNull(state);
        assertTrue(state.empty);
        assertTrue(state.trips.isEmpty());
    }

    @Test
    public void savedTripBecomesAnEnabledCardWithHeroUri() {
        // await(...) 를 대입 없이 문장으로 쓰면 javac 가 암시적 람다의 타입 인자를 Object 로
        // 잡아 컴파일에 실패한다(JLS 18.5.2, RoomTripRepositoryTest.deleteRemovesTheTripFromTheList
        // 참고) — 지역 변수 대입으로 타입 문맥을 준다.
        String tripId = await(cb -> analysisRepo.saveTrip("2024년 6월 여행", "Europe/Paris",
                Arrays.asList(placed(11L, "a.jpg", 1_718_154_720_000L),
                        placed(22L, "b.jpg", 1_718_158_320_000L)),
                cb));
        assertNotNull(tripId);

        HomeUiState state = refreshed();

        assertEquals(1, state.trips.size());
        HomeUiState.TripCard card = state.trips.get(0);
        assertEquals("2024년 6월 여행", card.title);
        assertTrue("실제 저장 여행은 열려야 한다", card.enabled);
        assertNotNull("hero 는 첫 사진의 content URI", card.heroPhotoUri);
        assertTrue(card.heroPhotoUri.toString().endsWith("/11"));
    }

    @Test
    public void cardMetaCarriesPhotoAndDayCounts() {
        String tripId = await(cb -> analysisRepo.saveTrip("여행", "Asia/Seoul",
                Collections.singletonList(placed(1L, "a.jpg", 1_718_154_720_000L)),
                cb));
        assertNotNull(tripId);

        String meta = refreshed().trips.get(0).meta;

        assertTrue("장수가 들어간다: " + meta, meta.contains("1"));
    }

    @Test
    public void fixtureCardsCarryNoHeroUriSoTheIllustrationPathHolds() {
        assertNull("픽스처는 일러스트 폴백을 타야 골든이 유지된다",
                com.traveltrace.app.ui.preview.ScreenFixtures.home()
                        .trips.get(0).heroPhotoUri);
    }
}
