package com.traveltrace.app.ui.map;

import androidx.annotation.ColorInt;

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

    public MapUiState(String tripTitle, int unknownCount, List<Stop> stops, int activeIndex,
                      boolean playing, boolean satellite, boolean cinema, Speed speed) {
        this.tripTitle = tripTitle;
        this.unknownCount = unknownCount;
        this.stops = Collections.unmodifiableList(stops);
        this.activeIndex = activeIndex;
        this.playing = playing;
        this.satellite = satellite;
        this.cinema = cinema;
        this.speed = speed;
    }

    public Stop activeStop() {
        return stops.get(Math.min(activeIndex, stops.size() - 1));
    }

    /** 경로 위 정차 지점 1곳. 좌표는 로직 단계(에픽 12)에서 붙는다. */
    public static final class Stop {
        public final String id;
        public final String name;
        public final String time;
        /** true 면 AI 근사 위치 (프로토타입 src:'ai') — 배지·점선 표식 대상. */
        public final boolean ai;
        /** 같은 지점의 추가 사진 수 ("+N장"). 0 이면 숨김. */
        public final int extra;
        @ColorInt public final int toneColor;

        public Stop(String id, String name, String time, boolean ai, int extra,
                    @ColorInt int toneColor) {
            this.id = id;
            this.name = name;
            this.time = time;
            this.ai = ai;
            this.extra = extra;
            this.toneColor = toneColor;
        }
    }
}
