package com.traveltrace.app.ui.map;

import androidx.annotation.ColorInt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** MAP 화면(상단바·하단시트·상영모드)이 렌더할 불변 상태. */
public final class MapUiState {

    /** 리플레이 속도 프리셋 (프로토타입 느긋이/보통/빠르게). */
    public enum Speed { RELAXED, NORMAL, FAST }

    public final String tripTitle;
    public final int unknownCount;
    public final List<Stop> stops;
    public final int activeIndex;
    public final boolean playing;
    public final boolean satellite;
    public final boolean cinema;
    public final Speed speed;

    /**
     * 지도 타일을 받아올 수 없는 상태. 경로 폴리라인은 저장된 좌표만으로 그려지므로 이 값이
     * true 여도 재생 자체는 정상이다 — 배경이 비는 이유를 안내하는 데만 쓴다(PRD §5).
     */
    public final boolean offline;

    /** offline=false 인 기존 8인자 형태. 오프라인을 모르는 호출부(픽스처·테스트)가 쓴다. */
    public MapUiState(String tripTitle, int unknownCount, List<Stop> stops, int activeIndex,
                      boolean playing, boolean satellite, boolean cinema, Speed speed) {
        this(tripTitle, unknownCount, stops, activeIndex, playing, satellite, cinema, speed, false);
    }

    public MapUiState(String tripTitle, int unknownCount, List<Stop> stops, int activeIndex,
                      boolean playing, boolean satellite, boolean cinema, Speed speed,
                      boolean offline) {
        this.tripTitle = tripTitle;
        this.unknownCount = unknownCount;
        this.stops = Collections.unmodifiableList(new ArrayList<>(stops));
        this.activeIndex = activeIndex;
        this.playing = playing;
        this.satellite = satellite;
        this.cinema = cinema;
        this.speed = speed;
        this.offline = offline;
    }

    /**
     * 연결 상태만 갈아끼운 복제본. <b>Stop 인스턴스는 그대로 넘긴다</b> —
     * {@link MapReplayFragment#sameRoute} 가 참조 동일성으로 "경로가 바뀌었는가"를 판별하므로,
     * 여기서 Stop 을 새로 찍으면 오프라인 배너가 뜨고 지는 것만으로 지도가 다시 그려지고
     * 카메라가 전체 경로 bounds 로 스냅된다.
     */
    public MapUiState withOffline(boolean offline) {
        if (this.offline == offline) return this;
        return new MapUiState(tripTitle, unknownCount, stops, activeIndex,
                playing, satellite, cinema, speed, offline);
    }

    public Stop activeStop() {
        if (stops.isEmpty()) {
            throw new IllegalStateException("activeStop() on a trip with no stops");
        }
        return stops.get(Math.max(0, Math.min(activeIndex, stops.size() - 1)));
    }

    /** 경로 위 정차 지점 1곳. */
    public static final class Stop {
        public final String id;
        public final String name;
        public final String time;
        /** true 면 AI 근사 위치 (프로토타입 src:'ai') — 배지·점선 표식 대상. */
        public final boolean ai;
        /** 같은 지점의 추가 사진 수 ("+N장"). 0 이면 숨김. */
        public final int extra;
        @ColorInt public final int toneColor;
        /** 지도에 찍을 좌표. PLACED 인 스톱만 여기 오므로 항상 유효하다. */
        public final double lat;
        public final double lng;

        public Stop(String id, String name, String time, boolean ai, int extra,
                    @ColorInt int toneColor, double lat, double lng) {
            this.id = id;
            this.name = name;
            this.time = time;
            this.ai = ai;
            this.extra = extra;
            this.toneColor = toneColor;
            this.lat = lat;
            this.lng = lng;
        }
    }
}
