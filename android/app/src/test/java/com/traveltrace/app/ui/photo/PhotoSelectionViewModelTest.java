package com.traveltrace.app.ui.photo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.provider.MediaStore;

import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.AsyncTestHarness;
import com.traveltrace.app.core.AppExecutors;
import com.traveltrace.app.data.media.MediaStoreImageSource;
import com.traveltrace.app.ui.selection.SelectionSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.fakes.RoboCursor;
import org.robolectric.shadows.ShadowLooper;

import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
public class PhotoSelectionViewModelTest {

    private Context ctx;
    private AppExecutors executors;
    private SelectionSession session;
    private PhotoSelectionViewModel vm;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        seed(new Object[]{11L, "a.jpg", 1_718_000_000_000L},
                new Object[]{22L, "b.jpg", 1_718_100_000_000L});

        executors = new AppExecutors();
        session = new SelectionSession();
        vm = new PhotoSelectionViewModel(
                ctx, new MediaStoreImageSource(ctx, executors), session);
    }

    @After
    public void tearDown() {
        executors.shutdown();
    }

    // Robolectric 4.14.1 의 ShadowContentResolver#setCursor 는 BaseCursor 서브타입만
    // 받는다(MatrixCursor 는 아니다) — 브리프의 MatrixCursor 를 그대로 쓰면 컴파일이 안 돼
    // RoboCursor(같은 BaseCursor 서브클래스)로 대체했다. MediaStoreImageSourceTest 와 동일한
    // 패턴이다.
    private void seed(Object[]... rows) {
        RoboCursor cursor = new RoboCursor();
        cursor.setColumnNames(Arrays.asList(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN));
        cursor.setResults(rows);
        Shadows.shadowOf(ctx.getContentResolver())
                .setCursor(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor);
    }

    /**
     * 콜백이 메인 루퍼로 오므로 테스트에서 루퍼를 비워 결과를 받는다.
     *
     * <p>실제 io() 는 진짜 백그라운드 스레드풀이라 vm.load() 호출 직후 한 번만
     * idleMainLooper() 를 불러선 안 된다 — 그 시점엔 아직 io 작업이 안 끝나 메인 루퍼에
     * 아무것도 안 쌓여 있을 수 있다(레이스). 상태가 도착할 때까지 짧게 반복해서 비운다.
     * RoomTripRepositoryTest 의 await 헬퍼와 같은 모양이다.
     */
    private PhotoSelectionUiState loadState() {
        vm.load();
        return awaitState();
    }

    private PhotoSelectionUiState awaitState() {
        long deadline = System.currentTimeMillis() + 5_000L;
        PhotoSelectionUiState state = vm.state().getValue();
        while (state == null && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper();
            state = vm.state().getValue();
            if (state == null) {
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
        }
        assertNotNull("state 가 5초 안에 와야 한다", state);
        return state;
    }

    @Test
    public void loadsGalleryImagesAsSelectedTiles() {
        PhotoSelectionUiState state = loadState();

        assertNotNull(state);
        assertEquals(2, state.tiles.size());
        assertTrue("새로 읽은 사진은 기본 선택 상태다", state.tiles.get(0).selected);
        assertEquals(11L, state.tiles.get(0).mediaStoreId);
        assertNotNull("실제 사진은 contentUri 를 갖는다", state.tiles.get(0).contentUri);
    }

    @Test
    public void togglePreservesIdentityAndUpdatesCount() {
        loadState();
        vm.toggle(0);
        ShadowLooper.idleMainLooper();

        PhotoSelectionUiState state = vm.state().getValue();
        assertEquals(1, state.selectedCount());
        assertEquals("해제해도 사진 식별자는 남는다", 11L, state.tiles.get(0).mediaStoreId);
    }

    @Test
    public void commitSelectionPushesSelectedIdsIntoTheSession() {
        loadState();
        vm.toggle(0);
        ShadowLooper.idleMainLooper();

        assertTrue(vm.commitSelection());
        assertEquals(Arrays.asList(22L), session.ids());
    }

    @Test
    public void commitWithNothingSelectedIsRejected() {
        loadState();
        vm.toggle(0);
        vm.toggle(1);
        ShadowLooper.idleMainLooper();

        assertFalse("한 장도 없으면 분석을 시작할 수 없다", vm.commitSelection());
        assertTrue(session.isEmpty());
    }

    @Test
    public void emptyGalleryProducesAnEmptyStateWithoutCrashing() {
        seed();

        PhotoSelectionUiState state = loadState();
        assertTrue(state.tiles.isEmpty());
        assertEquals(0, state.selectedCount());
    }

    /**
     * Finding 1 이 지키려는 것 그 자체: 회전으로 뷰만 재생성돼도 ViewModel 은 살아남고,
     * Fragment.onViewCreated 는 (판정 캐시를 null 로 되돌리므로) load() 를 다시 부른다.
     * 이 재호출이 사용자가 "탭하여 제외"로 골라 둔 선택을 기본값으로 덮어쓰면 안 된다.
     *
     * <p>이 테스트는 preservation 로직을 없애면(즉 toState 가 이전 상태를 무시하고 항상
     * i &lt; MAX_SELECTION 으로만 매기면) 반드시 실패한다 — 11L 을 다시 선택 상태로
     * 되돌려 버리기 때문이다.
     */
    @Test
    public void reloadingAfterRotationPreservesTheUsersExclusion() {
        loadState();
        vm.toggle(0); // 11L 제외
        ShadowLooper.idleMainLooper();
        assertEquals(1, vm.state().getValue().selectedCount());

        // MediaStoreImageSource.query() 가 커서를 try-with-resources 로 닫으므로, 같은
        // RoboCursor 인스턴스를 두 번째 query() 에도 그대로 돌려주는 setCursor 훅에서는
        // 재조회 전에 다시 seed() 해 새 커서를 등록해야 한다(그러지 않으면 이미 닫힌
        // 커서라 두 번째 query() 가 빈 목록을 내놓는다 — 이건 이 재로딩 계약과 무관한
        // 테스트 더블의 한계다).
        seed(new Object[]{11L, "a.jpg", 1_718_000_000_000L},
                new Object[]{22L, "b.jpg", 1_718_100_000_000L});

        // Fragment.onViewCreated 가 다시 부르는 vm.load() 를 흉내낸다 — ViewModel 은
        // 살아남았으므로 state() 는 이미 위에서 만든 (11L 제외) 상태를 들고 있다.
        // awaitState() 는 "state != null" 만 보므로 이미 non-null 인 상태에선 재로딩을
        // 기다리지 못한다 — 인스턴스 참조가 바뀔 때까지 기다리는 AsyncTestHarness 를 쓴다.
        PhotoSelectionUiState beforeReload = vm.state().getValue();
        PhotoSelectionUiState reloaded = AsyncTestHarness.awaitLiveData(
                vm.state(), vm::load, s -> s != beforeReload,
                "PhotoSelectionViewModel.load() reload");

        assertEquals(2, reloaded.tiles.size());
        assertFalse("재로딩해도 사용자가 제외한 사진은 계속 제외 상태여야 한다",
                reloaded.tiles.get(0).selected);
        assertEquals(11L, reloaded.tiles.get(0).mediaStoreId);
        assertTrue("건드리지 않은 사진은 그대로 선택 상태를 유지한다",
                reloaded.tiles.get(1).selected);
        assertEquals(1, reloaded.selectedCount());
    }

    /** 이전 상태에 없던(신규로 나타난) 사진은 여전히 기본 규칙(상한 이내 전체 선택)을 따른다. */
    @Test
    public void newlyAppearedPhotosOnReloadStillGetTheDefaultSelection() {
        loadState();
        vm.toggle(0); // 11L 제외
        ShadowLooper.idleMainLooper();

        // 재조회 사이에 새 사진이 갤러리에 나타난 상황(예: PARTIAL 재선택, 새 촬영).
        seed(new Object[]{11L, "a.jpg", 1_718_000_000_000L},
                new Object[]{22L, "b.jpg", 1_718_100_000_000L},
                new Object[]{33L, "c.jpg", 1_718_200_000_000L});

        PhotoSelectionUiState beforeReload = vm.state().getValue();
        PhotoSelectionUiState reloaded = AsyncTestHarness.awaitLiveData(
                vm.state(), vm::load, s -> s != beforeReload,
                "PhotoSelectionViewModel.load() reload with new photos");

        assertEquals(3, reloaded.tiles.size());
        assertFalse("기존에 제외했던 사진은 계속 제외 상태", reloaded.tiles.get(0).selected);
        assertTrue("기존에 선택돼 있던 사진은 그대로 선택 상태", reloaded.tiles.get(1).selected);
        assertTrue("처음 보는 사진은 기본값(상한 이내 전체 선택)을 받는다",
                reloaded.tiles.get(2).selected);
    }
}
