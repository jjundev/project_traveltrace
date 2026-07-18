package com.traveltrace.app.ui.map;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

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
import com.traveltrace.app.databinding.FragmentMapReplayBinding;

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

    private FragmentMapReplayBinding binding;
    private MapReplayViewModel vm;
    private GoogleMap map;

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

        // 지도는 전체 화면을 덮으므로 상단바에만 상태바 인셋을 준다.
        ViewCompat.setOnApplyWindowInsetsListener(binding.mapTopBar.topBarRoot, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), bars.top, v.getPaddingRight(), v.getPaddingBottom());
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

        vm.state().observe(getViewLifecycleOwner(), this::render);
    }

    private void render(MapUiState state) {
        MapRenderer.renderTopBar(binding.mapTopBar, state);
        binding.satelliteScrim.setVisibility(state.satellite ? View.VISIBLE : View.GONE);
        if (map != null) {
            map.setMapType(state.satellite ? GoogleMap.MAP_TYPE_SATELLITE : GoogleMap.MAP_TYPE_NORMAL);
        }
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        map = googleMap;
        map.getUiSettings().setMapToolbarEnabled(false);
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(PARIS, STATIC_ZOOM));

        MapUiState state = vm.state().getValue();
        if (state != null) render(state);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        map = null;
        binding = null;
    }
}
