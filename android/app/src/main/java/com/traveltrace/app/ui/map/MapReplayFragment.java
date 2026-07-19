package com.traveltrace.app.ui.map;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.traveltrace.app.R;
import com.traveltrace.app.databinding.FragmentMapReplayBinding;
import com.traveltrace.app.databinding.ViewMapBottomSheetBinding;
import com.traveltrace.app.ui.common.ToastPresenter;

import java.util.List;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * MAP: 정적 카메라의 실제 지도 + 크롬. 핀/경로/리플레이는 로직 에픽 소관이라
 * 이 단계에선 지도를 파리에 고정만 한다.
 */
@AndroidEntryPoint
public class MapReplayFragment extends Fragment implements OnMapReadyCallback {

    /** 프로토타입 데모 여행지 — 로직 단계에선 여행의 실제 bounds 로 대체된다. */
    public static final LatLng PARIS = new LatLng(48.8566, 2.3522);
    public static final float STATIC_ZOOM = 12f;

    public static final String ARG_TRIP_ID = "tripId";

    private FragmentMapReplayBinding binding;
    private MapReplayViewModel vm;
    private GoogleMap map;
    private OnBackPressedCallback cinemaBackCallback;

    /**
     * 마지막으로 지도에 그린 stops. togglePlay/setSpeed/setSatellite/setCinema/jumpTo/
     * next/prev 는 전부 같은 경로를 그대로 들고 새 MapUiState 를 내보내므로, 매 emission
     * 마다 다시 그리고 카메라를 whole-route bounds 로 되돌리면 사용자가 손으로 옮긴
     * 카메라 위치가 사라진다 — 이 필드는 그 사용자 카메라 위치를 지키는 가드다.
     */
    private List<MapUiState.Stop> lastDrawnStops;

    /** MAP 진입 인자. tripId 하나뿐이라 nav argument 로 나른다(사진 목록은 SelectionSession). */
    public static Bundle argsFor(String tripId) {
        Bundle args = new Bundle();
        args.putString(ARG_TRIP_ID, tripId);
        return args;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentMapReplayBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        vm = new ViewModelProvider(this).get(MapReplayViewModel.class);

        // 지도는 전체 화면을 덮으므로(fitsSystemWindows 불가) 상단바엔 상태바, 하단시트엔
        // 내비게이션 바 인셋을 직접 준다 — 디자인된 패딩(8dp/24dp)에 더하고, 덮어쓰지 않는다.
        // 인셋 콜백은 반복 호출될 수 있어 원래 패딩은 리스너 등록 전에 한 번만 캡처한다.
        View topBarRoot = binding.mapTopBar.topBarRoot;
        int topBarOriginalPaddingTop = topBarRoot.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(topBarRoot, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), topBarOriginalPaddingTop + bars.top,
                    v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });

        View sheetRoot = binding.mapBottomSheet.sheetRoot;
        int sheetOriginalPaddingBottom = sheetRoot.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(sheetRoot, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    sheetOriginalPaddingBottom + bars.bottom);
            return insets;
        });

        SupportMapFragment mapFragment =
                (SupportMapFragment) getChildFragmentManager().findFragmentById(
                        binding.mapContainer.getId());
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        binding.mapTopBar.mapBack.setOnClickListener(v ->
                NavHostFragment.findNavController(this).popBackStack());
        binding.mapTopBar.tabMap.setOnClickListener(v -> vm.setSatellite(false));
        binding.mapTopBar.tabSatellite.setOnClickListener(v -> vm.setSatellite(true));
        // finding 6(제품 결정): unknownCount 는 이제 진짜 Room 데이터라 칩 자체는 진짜
        // 여행에 맞게 뜨지만, 탭해서 여는 UnknownPhotosSheetFragment 는 아직
        // ScreenFixtures.unknownThumbTones() 의 하드코딩된 파스텔 6개짜리 draw다 —
        // 실제 위치 미상 사진(예: 12장)이어도 항상 6개의 가짜 스와치만 보여준다. 그래서
        // 이번 슬라이스에선 칩을 보이게는 두되(실제 개수 안내는 유효하다) 탭 핸들러는
        // 붙이지 않아 그 가짜 드로어에 닿을 길을 없앤다. UnknownPhotosSheetFragment 와
        // 그 스크린샷 커버리지는 다음 슬라이스가 실 데이터로 채울 때까지 그대로 둔다 —
        // 다음 사람이 "실수로 빠졌나 보다" 하고 다시 잇지 않도록, 이 주석이 그 이유를
        // 명시적으로 남긴다. 이 핸들러를 되살리려면 먼저 시트를 실 데이터로 채워야 한다.

        ViewMapBottomSheetBinding sheet = binding.mapBottomSheet;
        sheet.playButton.setOnClickListener(v -> vm.togglePlay());
        sheet.prevButton.setOnClickListener(v -> vm.prev());
        sheet.nextButton.setOnClickListener(v -> vm.next());
        sheet.cinemaButton.setOnClickListener(v -> vm.setCinema(true));
        sheet.scrubber.setOnStopSelectedListener(vm::jumpTo);
        sheet.speedRelaxed.setOnClickListener(v -> vm.setSpeed(MapUiState.Speed.RELAXED));
        sheet.speedNormal.setOnClickListener(v -> vm.setSpeed(MapUiState.Speed.NORMAL));
        sheet.speedFast.setOnClickListener(v -> vm.setSpeed(MapUiState.Speed.FAST));
        sheet.detachButton.setOnClickListener(v ->
                ToastPresenter.show(binding.getRoot(), getString(R.string.map_detach_toast)));

        binding.cinemaOverlay.cinemaRoot.setOnClickListener(v -> vm.setCinema(false));

        // 상영 모드에선 상단바가 숨어 탭-해제가 유일한 탈출구다 — Back 도 같은 동작을 하게 한다.
        // 상영 모드가 아닐 때는 비활성화해 Back 이 평소처럼 화면을 나가게 둔다.
        cinemaBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                vm.setCinema(false);
            }
        };
        requireActivity().getOnBackPressedDispatcher()
                .addCallback(getViewLifecycleOwner(), cinemaBackCallback);

        vm.load();
        vm.state().observe(getViewLifecycleOwner(), this::render);
    }

    private void render(MapUiState state) {
        if (binding == null) return;
        MapRenderer.renderTopBar(binding.mapTopBar, state);
        MapRenderer.renderSheet(binding.mapBottomSheet, state);
        binding.satelliteScrim.setVisibility(state.satellite ? View.VISIBLE : View.GONE);
        if (map != null) {
            map.setMapType(state.satellite ? GoogleMap.MAP_TYPE_SATELLITE : GoogleMap.MAP_TYPE_NORMAL);
            // 재생/속도/위성/상영/스크럽은 전부 "같은 경로, 다른 UI 상태" 인 emission 이다 —
            // MapUiState 생성자가 매번 unmodifiableList(new ArrayList<>(...)) 로 감싸므로 리스트
            // 참조는 항상 새것이지만(sameInstance 비교 불가), MapReplayViewModel.copy() 는 그 안의
            // Stop 인스턴스 자체는 복사하지 않고 그대로 넘긴다. 그래서 원소 참조 동일성으로
            // "경로가 실제로 바뀌었는가"를 판별한다 — 여행을 새로 열 때만 toState() 가 Stop 을
            // 통째로 새로 만들어서 이 비교가 깨진다. 경로가 안 바뀌었으면 다시 그리지도, 카메라를
            // whole-route bounds 로 되돌리지도 않는다 — 그게 사용자가 방금 옮긴 카메라를 지킨다.
            if (lastDrawnStops == null || !sameRoute(lastDrawnStops, state.stops)) {
                MapRouteRenderer.draw(map, state.stops, requireContext());
                lastDrawnStops = state.stops;
                com.google.android.gms.maps.CameraUpdate camera = MapRouteRenderer.cameraFor(
                        state.stops,
                        getResources().getDimensionPixelSize(R.dimen.map_camera_padding));
                if (camera != null) {
                    // 맵뷰 크기가 0인 콜드 스타트에 newLatLngBounds 를 쓰면 SDK 가 던진다 —
                    // 레이아웃이 끝난 뒤로 미룬다.
                    binding.mapContainer.post(() -> {
                        if (map != null) map.moveCamera(camera);
                    });
                }
            }
        }

        MapRenderer.renderCinema(binding.cinemaOverlay, state);
        int chromeVis = state.cinema ? View.GONE : View.VISIBLE;
        binding.mapTopBar.topBarRoot.setVisibility(chromeVis);
        binding.mapBottomSheet.sheetRoot.setVisibility(chromeVis);

        if (cinemaBackCallback != null) {
            cinemaBackCallback.setEnabled(state.cinema);
        }
    }

    /**
     * 두 stops 리스트가 "같은 경로"인지 — 리스트 컨테이너가 아니라 그 안의 Stop 인스턴스
     * 참조를 비교한다. MapUiState 가 매번 리스트를 새로 감싸기 때문에 컨테이너 참조 비교는
     * 항상 false 가 되어 무의미하다. GoogleMap 을 전혀 건드리지 않는 순수 판별이라 SDK 없이
     * 단위 테스트할 수 있도록 package-private 으로 둔다.
     */
    static boolean sameRoute(List<MapUiState.Stop> a, List<MapUiState.Stop> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) return false;
        }
        return true;
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        if (binding == null) return;
        map = googleMap;
        map.getUiSettings().setMapToolbarEnabled(false);

        // 초기 카메라는 여행 스톱에서 결정된다 — 스톱이 없을 때만 파리 고정.
        MapUiState current = vm.state().getValue();
        if (current == null || current.stops.isEmpty()) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(PARIS, STATIC_ZOOM));
        }

        MapUiState state = vm.state().getValue();
        if (state != null) render(state);
    }

    @Override
    public void onDestroyView() {
        ToastPresenter.cancel(binding.getRoot());
        super.onDestroyView();
        map = null;
        binding = null;
        // 뷰(따라서 GoogleMap)가 새로 만들어지면 그 위엔 아직 아무것도 그려져 있지 않다 —
        // 캐시된 last-drawn 경로를 버려서 다음 onMapReady 가 (VM 의 Stop 인스턴스가 그대로여도)
        // 반드시 다시 그리게 한다.
        lastDrawnStops = null;
    }
}
