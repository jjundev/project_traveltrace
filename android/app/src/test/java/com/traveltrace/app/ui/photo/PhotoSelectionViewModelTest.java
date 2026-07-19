package com.traveltrace.app.ui.photo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.provider.MediaStore;

import androidx.test.core.app.ApplicationProvider;

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
}
