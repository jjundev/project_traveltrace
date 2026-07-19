package com.traveltrace.app.ui.photo;

import android.content.Context;

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
import java.util.List;
import java.util.Locale;

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

    /** 권한이 확보된 뒤 Fragment 가 호출한다. 여러 번 불러도 안전하다. */
    public void load() {
        imageSource.loadRecent(GALLERY_PAGE, images -> state.setValue(toState(images)));
    }

    private PhotoSelectionUiState toState(List<GalleryImage> images) {
        List<PhotoSelectionUiState.Tile> tiles = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            GalleryImage image = images.get(i);
            tiles.add(new PhotoSelectionUiState.Tile(
                    TONES[i % TONES.length],
                    null,
                    // 기본 전체 선택 — 사용자는 "탭하여 제외"한다(프로토타입 문구).
                    i < MAX_SELECTION,
                    image.id,
                    image.contentUri));
        }
        return new PhotoSelectionUiState(periodLabel(images), MAX_SELECTION, tiles);
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
