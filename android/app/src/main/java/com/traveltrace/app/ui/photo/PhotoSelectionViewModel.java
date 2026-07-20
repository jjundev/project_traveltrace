package com.traveltrace.app.ui.photo;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.traveltrace.app.R;
import com.traveltrace.app.data.media.GalleryImage;
import com.traveltrace.app.data.media.MediaStoreImageSource;
import com.traveltrace.app.ui.selection.SelectionSession;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * SELECT 데이터. 갤러리에서 최근 사진을 읽어 타일로 만들고, 확정된 선택을
 * SelectionSession 에 실어 ANALYZE 로 넘긴다.
 *
 * <p>썸네일 타일에 톤 색이 남아 있는 이유: Glide 로딩 전/실패 시의 placeholder 다.
 */
@HiltViewModel
public class PhotoSelectionViewModel extends ViewModel {

    /** PRD §4.1: 한 번에 최대 ~100장 권장. */
    public static final int MAX_SELECTION = 100;

    /** 그리드에 올릴 최근 사진 상한. 선택 상한(100)보다 넉넉해야 고를 여지가 있다. */
    private static final int GALLERY_PAGE = 500;

    /** Glide 로딩 전 placeholder 로 쓰는 톤 팔레트(프로토타입 TONES 계열). */
    private static final int[] TONES = {
            0xFFDBE4EE, 0xFFE8E0D6, 0xFFDDE8E1, 0xFFE6DDE6, 0xFFE7E1D6, 0xFFD8E1EA};

    private final Context context;
    private final MediaStoreImageSource imageSource;
    private final SelectionSession session;
    private final MutableLiveData<PhotoSelectionUiState> state = new MutableLiveData<>();

    @Inject
    public PhotoSelectionViewModel(@ApplicationContext Context context,
                                   MediaStoreImageSource imageSource,
                                   SelectionSession session) {
        this.context = context;
        this.imageSource = imageSource;
        this.session = session;
    }

    public LiveData<PhotoSelectionUiState> state() {
        return state;
    }

    /**
     * 권한이 확보된 뒤 Fragment 가 호출한다. 여러 번 불러도 안전하다 — 그리고 회전처럼
     * 뷰만 재생성되고 이 ViewModel 이 살아남는 경우(finding 1)에도 안전해야 한다: 이미
     * 반영된 선택(사용자가 "탭하여 선택"으로 골라 둔 것)을 여기서 기본값으로 덮어쓰면 안
     * 되므로, 직전 상태가 있으면 mediaStoreId 기준으로 selected 를 그대로 이어받는다.
     * PARTIAL 권한에서 매 resume 마다 다시 불리는 건 의도된 동작이다(사용자가 시스템의
     * "사진 더 선택"에서 목록 자체를 바꿀 수 있어서다) — 여기서 막는 건 그 재조회 자체가
     * 아니라 "재조회가 곧 선택 초기화"였던 부작용이다.
     */
    public void load() {
        PhotoSelectionUiState previous = state.getValue();
        imageSource.loadRecent(GALLERY_PAGE, images -> state.setValue(toState(images, previous)));
    }

    private PhotoSelectionUiState toState(List<GalleryImage> images,
                                          @Nullable PhotoSelectionUiState previous) {
        Map<Long, Boolean> previousSelection = new HashMap<>();
        if (previous != null) {
            for (PhotoSelectionUiState.Tile tile : previous.tiles) {
                previousSelection.put(tile.mediaStoreId, tile.selected);
            }
        }

        List<PhotoSelectionUiState.Tile> tiles = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            GalleryImage image = images.get(i);
            Boolean carried = previousSelection.get(image.id);
            // 이미 알던 사진(id 가 이전 상태에도 있었다)은 사용자가 정한 선택을 그대로
            // 이어받는다. 처음 보는 사진(신규 촬영분 등)은 미선택으로 시작한다 —
            // "탭하여 선택" UX(사용자가 여행에 넣을 사진만 직접 고른다).
            boolean selected = carried != null && carried;
            tiles.add(new PhotoSelectionUiState.Tile(
                    TONES[i % TONES.length],
                    null,
                    selected,
                    image.id,
                    image.contentUri));
        }
        return new PhotoSelectionUiState(periodLabel(images), MAX_SELECTION,
                context.getString(R.string.select_album_all), tiles);
    }

    /** "2024. 6. 12 – 6. 15 · 사진 94장" 형태. 시각을 모르는 사진은 기간 계산에서 뺀다. */
    private String periodLabel(List<GalleryImage> images) {
        Long earliest = null;
        Long latest = null;
        for (GalleryImage image : images) {
            if (image.dateTakenUtc == null) continue;
            if (earliest == null || image.dateTakenUtc < earliest) earliest = image.dateTakenUtc;
            if (latest == null || image.dateTakenUtc > latest) latest = image.dateTakenUtc;
        }
        if (earliest == null) {
            return context.getString(R.string.select_period_empty);
        }
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy. M. d", Locale.KOREA);
        return context.getString(R.string.select_period_range,
                fmt.format(new Date(earliest)), fmt.format(new Date(latest)), images.size());
    }

    public void toggle(int index) {
        PhotoSelectionUiState current = state.getValue();
        if (current == null) return;
        state.setValue(current.withToggled(index));
    }

    /**
     * 선택을 확정해 세션에 싣는다. 한 장도 없으면 false 를 돌려주고 아무것도 하지 않는다
     * — 호출부가 화면 전환을 막는다.
     */
    public boolean commitSelection() {
        PhotoSelectionUiState current = state.getValue();
        if (current == null) return false;
        List<Long> ids = current.selectedMediaStoreIds();
        if (ids.isEmpty()) return false;
        session.put(ids);
        return true;
    }
}
