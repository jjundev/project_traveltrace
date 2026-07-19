package com.traveltrace.app.ui.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.AsyncTestHarness;
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

import java.util.Arrays;
import java.util.Collections;
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
     * {@link AsyncTestHarness#awaitCallback} 로 위임한다. 예전 로컬 구현은 콜백이 메인 루퍼에서
     * 오는지 검증을 빠뜨리고 있었다 — 공유 헬퍼로 옮기면서 그 드리프트를 없앤다(
     * {@link AsyncTestHarness} 자바독의 "무조건 검증" 결정 참고).
     */
    private static <T> T await(Consumer<Callback<T>> call) {
        return AsyncTestHarness.awaitCallback(call);
    }

    /**
     * {@link AsyncTestHarness#awaitLiveData} 로 위임한다. vm.refresh() 는 TripRepository.list()
     * 를 거쳐 같은 io() 스레드풀을 타므로 단발 idleMainLooper() 는 레이스가 난다 — 이유는
     * {@link AsyncTestHarness} 자바독 참고. state() LiveData 값이 새 인스턴스로 바뀔 때까지
     * 기다린다(HomeUiState.toState() 는 매번 새 인스턴스를 만들므로 "이전과 다른 참조"가 곧
     * "새로고침 반영됨"이다).
     */
    private HomeUiState refreshed() {
        HomeUiState previous = vm.state().getValue();
        return AsyncTestHarness.awaitLiveData(
                vm.state(), vm::refresh, current -> current != previous, "HomeViewModel.refresh()");
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

    /**
     * finding 3: HOME 에 삭제 통로가 생긴 이유 그 자체 — 취소됐지만 이미 커밋된 분석이
     * 여행 하나를 남기는 수용된 레이스(AnalysisViewModel finding 2)를 사용자가 직접
     * 치울 수 있어야 한다. delete() 는 리포지토리에서 지운 뒤 목록을 새로고침해야 한다.
     */
    @Test
    public void deleteRemovesTheTripAndRefreshesTheList() {
        String tripId = await(cb -> analysisRepo.saveTrip("지울 여행", "Asia/Seoul",
                Collections.singletonList(placed(1L, "a.jpg", 1_718_154_720_000L)), cb));
        assertNotNull(tripId);
        assertEquals(1, refreshed().trips.size());

        HomeUiState afterDelete = AsyncTestHarness.awaitLiveData(
                vm.state(), () -> vm.delete(tripId),
                state -> state.empty, "HomeViewModel.delete()");

        assertTrue("삭제 후 목록에서 사라져야 한다", afterDelete.trips.isEmpty());
    }
}
