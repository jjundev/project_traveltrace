package com.traveltrace.app.ui.home;

import android.content.ContentUris;
import android.content.Context;
import android.provider.MediaStore;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.traveltrace.app.R;
import com.traveltrace.app.domain.TripRepository;
import com.traveltrace.app.domain.model.TripSummary;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import dagger.hilt.android.qualifiers.ApplicationContext;

/** HOME 데이터. 저장된 여행 목록을 Room 에서 읽는다 — AI·EXIF 재실행은 없다. */
@HiltViewModel
public class HomeViewModel extends ViewModel {

    private final Context context;
    private final TripRepository tripRepository;
    private final MutableLiveData<HomeUiState> state = new MutableLiveData<>();

    @Inject
    public HomeViewModel(@ApplicationContext Context context, TripRepository tripRepository) {
        this.context = context;
        this.tripRepository = tripRepository;
    }

    public LiveData<HomeUiState> state() {
        return state;
    }

    /** 화면에 돌아올 때마다 호출한다 — 분석 후 새 여행이 바로 보여야 한다. */
    public void refresh() {
        tripRepository.list(summaries -> state.setValue(toState(summaries)));
    }

    private HomeUiState toState(List<TripSummary> summaries) {
        if (summaries.isEmpty()) {
            return HomeUiState.empty();
        }
        List<HomeUiState.TripCard> cards = new ArrayList<>();
        for (TripSummary s : summaries) {
            cards.add(new HomeUiState.TripCard(
                    s.id,
                    s.name,
                    meta(s),
                    // 위치 라벨은 역지오코딩이 필요해 S1 범위 밖이다 — pill 을 숨긴다.
                    null,
                    true,
                    s.heroMediaStoreId == null ? null : ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, s.heroMediaStoreId)));
        }
        return HomeUiState.trips(cards);
    }

    /** "82장 · 4일 · 2024. 6" (프로토타입 카드 메타). */
    private String meta(TripSummary s) {
        String yearMonth = new SimpleDateFormat("yyyy. M", Locale.KOREA)
                .format(new Date(s.startDateUtc));
        return context.getString(R.string.home_trip_meta,
                s.photoCount, s.dayCount, yearMonth);
    }
}
