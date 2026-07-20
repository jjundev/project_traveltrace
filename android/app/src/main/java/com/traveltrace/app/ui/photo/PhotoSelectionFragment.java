package com.traveltrace.app.ui.photo;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;

import com.traveltrace.app.R;
import com.traveltrace.app.data.media.AlbumBucket;
import com.traveltrace.app.databinding.FragmentPhotoSelectionBinding;
import com.traveltrace.app.ui.common.ToastPresenter;

import java.util.ArrayList;
import java.util.List;

import dagger.hilt.android.AndroidEntryPoint;

/** SELECT: 권한 → 갤러리 로딩 → 3열 선택 그리드 → 분석 시작. */
@AndroidEntryPoint
public class PhotoSelectionFragment extends Fragment
        implements AlbumPickerSheetFragment.Listener {

    private FragmentPhotoSelectionBinding binding;
    private PhotoSelectionViewModel vm;
    private ActivityResultLauncher<String[]> permissionLauncher;

    /**
     * onViewCreated 직후의 첫 onResume 은 건너뛴다 — 그 시점엔 이미 onViewCreated 가
     * (권한 요청을 걸었거나 applyPermissionState 를 직접 불러) 초기 판정을 처리한 뒤이고,
     * DENIED 로 시작한 경우엔 아직 시스템 다이얼로그 응답조차 오지 않은 상태라 여기서
     * 재평가하면 다이얼로그가 뜨기도 전에 "거부됨" 토스트가 먼저 튀는 부작용이 생긴다.
     * 그 다음부터의 onResume(설정 화면 왕복, 백그라운드 복귀 등)은 정상적으로 재평가한다.
     */
    private boolean skipNextResumeRefresh;

    /**
     * 마지막으로 반영한 판정. 같은 판정이 반복되면 갤러리 재조회/토스트를 또 띄우지 않기
     * 위한 최소한의 캐시다 — 화면이 재생성되면(onViewCreated) 반드시 null 로 되돌려
     * 새 바인딩에 최초 1회는 무조건 반영되게 한다.
     */
    @Nullable
    private MediaPermissionController.State lastAppliedState;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 런처는 STARTED 이전에 등록해야 한다 — onViewCreated 에서 등록하면 예외가 난다.
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> applyPermissionState());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentPhotoSelectionBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        vm = new ViewModelProvider(this).get(PhotoSelectionViewModel.class);

        // 새로 생성된 바인딩이다 — 이전 뷰 인스턴스에서 캐시해 둔 판정은 의미가 없으니
        // 버린다(그렇지 않으면 아래 applyPermissionState 가 "판정 그대로"로 보고
        // 새 버튼에 리스너를 한 번도 안 묶는 사고가 난다).
        lastAppliedState = null;
        skipNextResumeRefresh = true;

        binding.selectBack.setOnClickListener(v ->
                NavHostFragment.findNavController(this).popBackStack());
        binding.albumPicker.setOnClickListener(v -> openAlbumPicker());

        bindNormalStartAnalyzeButton();

        vm.state().observe(getViewLifecycleOwner(), state ->
                PhotoSelectionRenderer.render(binding, state, vm::toggle));

        if (MediaPermissionController.evaluate(requireContext())
                == MediaPermissionController.State.DENIED) {
            permissionLauncher.launch(MediaPermissionController.requiredPermissions());
        } else {
            applyPermissionState();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // onViewCreated 직후의 첫 onResume 은 위에서 이미 처리한 초기 판정과 중복되거나
        // (DENIED 로 시작한 경우) 시스템 다이얼로그 응답보다 앞서 실행되므로 건너뛴다.
        if (skipNextResumeRefresh) {
            skipNextResumeRefresh = false;
            return;
        }
        // 설정 화면에 다녀오거나 앱이 다시 포그라운드로 올라올 때마다 판정을 다시 확인한다
        // — applyPermissionState 자체가 "판정이 그대로면 아무것도 안 한다"를 보장하므로
        // 여기서 매번 불러도 갤러리 재조회나 토스트 스팸은 나지 않는다.
        applyPermissionState();
    }

    /** 권한 판정에 따라 그리드를 채우거나 차단 안내를 띄운다. */
    private void applyPermissionState() {
        if (binding == null) return;
        MediaPermissionController.State state =
                MediaPermissionController.evaluate(requireContext());

        // 직전과 같은 판정이면 조용히 넘어간다 — 아니면 화면이 포그라운드로 돌아올 때마다
        // (권한 변화가 전혀 없어도) 갤러리를 다시 읽고 토스트를 또 띄우게 된다.
        // 단, PARTIAL 은 이 캐시에서 제외한다: State 는 "어떤 권한을 들고 있는지"만
        // 말해줄 뿐 "어떤 사진이 선택됐는지"는 말해주지 않는다. 사용자가 시스템의
        // "사진 더 선택" 화면에서 선택 목록을 바꾸고 돌아와도 evaluate() 는 여전히
        // PARTIAL 을 반환하므로, 판정이 같다는 이유로 여기서 건너뛰면 그리드가 낡은
        // 목록을 보여준 채 멈춘다. 그래서 PARTIAL 일 때는 판정이 그대로여도 매번
        // vm.load() 를 다시 불러 그리드를 최신 선택 목록으로 갱신한다.
        boolean unchanged = state == lastAppliedState;
        if (unchanged && state != MediaPermissionController.State.PARTIAL) return;
        lastAppliedState = state;

        switch (state) {
            case GRANTED:
                bindNormalStartAnalyzeButton();
                vm.load();
                break;
            case PARTIAL:
                // 부분 허용도 읽을 수 있다 — 목록을 채우되 "더 선택하기"를 안내한다.
                bindNormalStartAnalyzeButton();
                vm.load();
                // 단, 안내 토스트는 판정이 실제로 바뀐 경우(예: DENIED→PARTIAL, 최초
                // 진입)에만 띄운다 — PARTIAL 이 그대로 유지된 채 목록만 갱신되는
                // resume 마다 같은 토스트가 반복되면 스팸이 된다. 그리드 갱신은
                // 침묵 속에 일어나도 무해하지만, 토스트는 매번 뜨면 오히려 소음이다.
                if (!unchanged) {
                    ToastPresenter.show(binding.getRoot(),
                            getString(R.string.select_permission_partial));
                }
                break;
            case DENIED:
            default:
                // 안내를 먼저 띄우고, 사용자가 읽을 수 있게 설정 이동은 CTA 로 넘긴다.
                ToastPresenter.show(binding.getRoot(),
                        getString(R.string.select_permission_denied));
                bindSettingsStartAnalyzeButton();
                break;
        }
    }

    /**
     * startAnalyzeButton 을 "선택 확정 후 분석으로 이동"이라는 원래 역할로 묶는다.
     * GRANTED/PARTIAL 판정일 때 쓰며, 설정 화면에 다녀와 권한이 풀린 뒤에도 이 메서드로
     * 되돌아오므로 버튼이 openAppSettings() 에 눌어붙어 있지 않는다.
     */
    private void bindNormalStartAnalyzeButton() {
        binding.startAnalyzeButton.setText(R.string.select_start_analyze);
        binding.startAnalyzeButton.setOnClickListener(v -> {
            if (vm.commitSelection()) {
                NavHostFragment.findNavController(this)
                        .navigate(R.id.action_photo_to_analysis);
            } else {
                // commitSelection() 이 false 인 유일한 조건은 "한 장도 안 골랐다"이다.
                ToastPresenter.show(binding.getRoot(),
                        getString(R.string.select_none_selected));
            }
        });
    }

    /**
     * startAnalyzeButton 을 임시로 "설정 열기" CTA 로 바꿔 묶는다. DENIED 판정일 때만
     * 쓰며, 라벨도 함께 바꿔 분석 시작처럼 보이지 않게 한다.
     */
    private void bindSettingsStartAnalyzeButton() {
        binding.startAnalyzeButton.setText(R.string.select_open_settings);
        binding.startAnalyzeButton.setOnClickListener(v -> openAppSettings());
    }

    /**
     * 기기 앨범 목록을 조회해 "전체 사진" 합성 항목을 맨 앞에 붙인 뒤 시트를 띄운다.
     * 그 라벨은 한국어 문자열 리소스가 필요한 UI 결정이라 데이터 계층
     * ({@code MediaStoreImageSource.loadAlbums}) 이 아니라 여기서 만든다.
     */
    private void openAlbumPicker() {
        vm.loadAlbums(albums -> {
            if (binding == null) return; // 조회 도중 화면을 나갔을 수 있다.

            int total = 0;
            for (AlbumBucket album : albums) total += album.count;

            List<AlbumBucket> withAllPhotos = new ArrayList<>();
            withAllPhotos.add(new AlbumBucket(null, getString(R.string.select_album_all), total));
            withAllPhotos.addAll(albums);

            AlbumPickerSheetFragment.newInstance(withAllPhotos, vm.selectedBucketId())
                    .show(getChildFragmentManager(), AlbumPickerSheetFragment.TAG);
        });
    }

    @Override
    public void onAlbumSelected(@Nullable String bucketId, String displayName) {
        vm.selectAlbum(bucketId, displayName);
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", requireContext().getPackageName(), null));
        startActivity(intent);
    }

    @Override
    public void onDestroyView() {
        ToastPresenter.cancel(binding.getRoot());
        super.onDestroyView();
        binding = null;
        // 뷰가 죽으면 그 뷰에 대해 반영했던 판정 캐시도 함께 버린다 — 다음 onViewCreated 가
        // 어차피 다시 null 로 밀지만, 뷰 스코프 상태는 뷰 수명에 묶어 두는 게 안전하다.
        lastAppliedState = null;
    }
}
