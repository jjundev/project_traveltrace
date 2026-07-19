package com.traveltrace.app.ui.preview;

import com.traveltrace.app.ui.analysis.AnalysisUiState;
import com.traveltrace.app.ui.home.HomeUiState;
import com.traveltrace.app.ui.map.MapUiState;
import com.traveltrace.app.ui.photo.PhotoSelectionUiState;

import java.util.ArrayList;
import java.util.List;

/**
 * 화면-우선 구현 단계의 임시 데이터 공급원. 값은 프로토타입
 * (prototype/TravelTrace.html 의 STOPS()/TONES()/GRIDMETA/state.grid)과 1:1로 일치한다.
 *
 * <p>로직 에픽에서 각 ViewModel 의 공급원이 Repository 로 교체되면 이 클래스는 삭제된다.
 * Renderer·레이아웃은 그때도 바뀌지 않는다 — UiState 가 경계다.
 */
public final class ScreenFixtures {

    private ScreenFixtures() {}

    private static final int TOTAL_PHOTOS = 82;

    // ---- HOME ----

    public static HomeUiState home() {
        List<HomeUiState.TripCard> trips = new ArrayList<>();
        trips.add(new HomeUiState.TripCard(
                "paris", "2024 파리 여행", "82장 · 4일 · 2024. 6",
                "🇫🇷 파리 · 프랑스", true));
        // 제주 카드는 프로토타입에서 시각 전용(openTripLocked → 토스트).
        trips.add(new HomeUiState.TripCard(
                "jeju", "2023 제주 가족여행", "63장 · 3일 · 2023. 10",
                "🌋 제주 · 한국", false));
        return HomeUiState.trips(trips);
    }

    public static HomeUiState homeEmpty() {
        return HomeUiState.empty();
    }

    // ---- SELECT ----

    /** GRIDMETA 18타일: {tone, label(nullable), 초기 선택}. index 4·11 만 해제. */
    public static PhotoSelectionUiState photoSelection() {
        List<PhotoSelectionUiState.Tile> tiles = new ArrayList<>();
        tiles.add(tile(0xFFDBE4EE, null, true));
        tiles.add(tile(0xFFE8E0D6, "개선문", true));
        tiles.add(tile(0xFFDDE8E1, null, true));
        tiles.add(tile(0xFFE6DDE6, null, true));
        tiles.add(tile(0xFFE7E1D6, "음식", false));
        tiles.add(tile(0xFFD8E1EA, "에펠탑", true));
        tiles.add(tile(0xFFDFE7EC, null, true));
        tiles.add(tile(0xFFE4E8E0, null, true));
        tiles.add(tile(0xFFE8E2DA, null, true));
        tiles.add(tile(0xFFD9E3EC, "센강", true));
        tiles.add(tile(0xFFE3DDE6, null, true));
        tiles.add(tile(0xFFEAE4DA, "실내", false));
        tiles.add(tile(0xFFDDE6E8, null, true));
        tiles.add(tile(0xFFE6E0D8, "루브르", true));
        tiles.add(tile(0xFFDCE5EE, null, true));
        tiles.add(tile(0xFFE7E2DD, null, true));
        tiles.add(tile(0xFFDDE8E3, "몽마르트", true));
        tiles.add(tile(0xFFE4DEE6, null, true));
        return new PhotoSelectionUiState("2024. 6. 12 – 6. 15 · 사진 94장", 100, tiles);
    }

    /** 픽스처는 실제 사진이 없다 — mediaStoreId 0, contentUri null 로 톤 색 경로를 탄다. */
    private static PhotoSelectionUiState.Tile tile(int tone, String label, boolean selected) {
        return new PhotoSelectionUiState.Tile(tone, label, selected, 0L, null);
    }

    // ---- ANALYZE ----

    public static AnalysisUiState analysisInProgress(int analyzed) {
        return new AnalysisUiState(analyzed, TOTAL_PHOTOS, false, recognizedAt(analyzed), 0, 0);
    }

    public static AnalysisUiState analysisDone() {
        return new AnalysisUiState(TOTAL_PHOTOS, TOTAL_PHOTOS, true, "몽마르트", 6, 5);
    }

    /**
     * 프로토타입 recogName(): analyzed 가 각 정차점의 lit 임계치를 넘을 때마다
     * 마지막으로 밝혀진 장소 이름을 보여준다. lit = {8, 22, 38, 54, 68, 80}.
     */
    private static String recognizedAt(int analyzed) {
        int[] lit = {8, 22, 38, 54, 68, 80};
        String[] names = {"개선문", "에펠탑", "센강 유람선", "루브르 박물관", "노트르담", "몽마르트"};
        String current = "사진 읽는 중";
        for (int i = 0; i < lit.length; i++) {
            if (analyzed >= lit[i]) current = names[i];
        }
        return current;
    }

    // ---- MAP ----

    public static MapUiState map() {
        List<MapUiState.Stop> stops = new ArrayList<>();
        stops.add(new MapUiState.Stop("arc", "개선문", "10:12", false, 0, 0xFFD9C9A8));
        stops.add(new MapUiState.Stop("eiffel", "에펠탑", "11:05", false, 4, 0xFFB7C6D6));
        stops.add(new MapUiState.Stop("seine", "센강 유람선", "13:20", false, 2, 0xFFA9C6DA));
        stops.add(new MapUiState.Stop("louvre", "루브르 박물관", "15:40", true, 0, 0xFFCDBFA1));
        stops.add(new MapUiState.Stop("notredame", "노트르담", "16:50", false, 0, 0xFFC3B69B));
        stops.add(new MapUiState.Stop("sacre", "몽마르트", "18:30", false, 3, 0xFFD7D0BF));
        return new MapUiState("2024 파리 여행", 5, stops, 0, false, false, false,
                MapUiState.Speed.NORMAL);
    }

    /** 위치 미상 드로어의 썸네일 5개 톤. */
    public static int[] unknownThumbTones() {
        return new int[]{0xFFE3D6C8, 0xFFD6DEE6, 0xFFE0DCE4, 0xFFDDE6DF, 0xFFE6DDD4};
    }

    /** 상영 모드 카드의 도시 라벨 (프로토타입 "시각 · 파리"). */
    public static String cityLabel() {
        return "파리";
    }

}
