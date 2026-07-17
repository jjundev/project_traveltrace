package com.traveltrace.app.ui.home;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** HOME 화면이 렌더할 불변 상태. 리소스 ID를 담지 않는다 — 순수 JUnit 테스트 대상. */
public final class HomeUiState {

    public final boolean empty;
    public final List<TripCard> trips;

    private HomeUiState(boolean empty, List<TripCard> trips) {
        this.empty = empty;
        this.trips = Collections.unmodifiableList(new ArrayList<>(trips));
    }

    public static HomeUiState trips(List<TripCard> trips) {
        return new HomeUiState(false, trips);
    }

    public static HomeUiState empty() {
        return new HomeUiState(true, Collections.<TripCard>emptyList());
    }

    /** 여행 카드 1장. 카드 상단 140dp hero 일러스트는 TripCardAdapter 가 id 로 고른다. */
    public static final class TripCard {
        public final String id;
        public final String title;
        public final String meta;
        @Nullable public final String locationLabel;
        /** false 면 탭 시 토스트만 띄운다 (프로토타입 openTripLocked). */
        public final boolean enabled;

        public TripCard(String id, String title, String meta, @Nullable String locationLabel,
                        boolean enabled) {
            this.id = id;
            this.title = title;
            this.meta = meta;
            this.locationLabel = locationLabel;
            this.enabled = enabled;
        }
    }
}
