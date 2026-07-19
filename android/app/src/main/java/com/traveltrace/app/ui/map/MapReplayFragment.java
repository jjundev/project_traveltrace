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
        binding.mapTopBar.unknownChip.setOnClickListener(v -> {
            MapUiState state = vm.state().getValue();
            if (state == null) return;
            // 빠른 연타로 두 번 뜨지 않도록 가드 (AnalysisFragment 의 TimezoneSheetFragment 가드와 동일 패턴).
            if (getChildFragmentManager().findFragmentByTag(UnknownPhotosSheetFragment.TAG) != null) {
                return;
            }
            // 톤은 VM 이 공급한다 (seam 규칙 — Fragment/시트는 ScreenFixtures 를 직접 부르지 않는다).
            UnknownPhotosSheetFragment.newInstance(state.unknownCount, vm.unknownThumbTones())
                    .show(getChildFragmentManager(), UnknownPhotosSheetFragment.TAG);
        });

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

        vm.state().observe(getViewLifecycleOwner(), this::render);
    }

    private void render(MapUiState state) {
        if (binding == null) return;
        MapRenderer.renderTopBar(binding.mapTopBar, state);
        MapRenderer.renderSheet(binding.mapBottomSheet, state);
        binding.satelliteScrim.setVisibility(state.satellite ? View.VISIBLE : View.GONE);
        if (map != null) {
            map.setMapType(state.satellite ? GoogleMap.MAP_TYPE_SATELLITE : GoogleMap.MAP_TYPE_NORMAL);
        }

        MapRenderer.renderCinema(binding.cinemaOverlay, state);
        int chromeVis = state.cinema ? View.GONE : View.VISIBLE;
        binding.mapTopBar.topBarRoot.setVisibility(chromeVis);
        binding.mapBottomSheet.sheetRoot.setVisibility(chromeVis);

        if (cinemaBackCallback != null) {
            cinemaBackCallback.setEnabled(state.cinema);
        }
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        if (binding == null) return;
        map = googleMap;
        map.getUiSettings().setMapToolbarEnabled(false);
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(PARIS, STATIC_ZOOM));

        MapUiState state = vm.state().getValue();
        if (state != null) render(state);
    }

    @Override
    public void onDestroyView() {
        ToastPresenter.cancel(binding.getRoot());
        super.onDestroyView();
        map = null;
        binding = null;
    }
}
