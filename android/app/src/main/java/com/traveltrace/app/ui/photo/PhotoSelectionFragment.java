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
import com.traveltrace.app.databinding.FragmentPhotoSelectionBinding;
import com.traveltrace.app.ui.common.ToastPresenter;

import dagger.hilt.android.AndroidEntryPoint;

/** SELECT: 권한 → 갤러리 로딩 → 3열 선택 그리드 → 분석 시작. */
@AndroidEntryPoint
public class PhotoSelectionFragment extends Fragment {

    private FragmentPhotoSelectionBinding binding;
    private PhotoSelectionViewModel vm;
    private ActivityResultLauncher<String[]> permissionLauncher;

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

        binding.selectBack.setOnClickListener(v ->
                NavHostFragment.findNavController(this).popBackStack());

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

        vm.state().observe(getViewLifecycleOwner(), state ->
                PhotoSelectionRenderer.render(binding, state, vm::toggle));

        if (MediaPermissionController.evaluate(requireContext())
                == MediaPermissionController.State.DENIED) {
            permissionLauncher.launch(MediaPermissionController.requiredPermissions());
        } else {
            applyPermissionState();
        }
    }

    /** 권한 판정에 따라 그리드를 채우거나 차단 안내를 띄운다. */
    private void applyPermissionState() {
        if (binding == null) return;
        MediaPermissionController.State state =
                MediaPermissionController.evaluate(requireContext());

        switch (state) {
            case GRANTED:
                vm.load();
                break;
            case PARTIAL:
                // 부분 허용도 읽을 수 있다 — 목록을 채우되 "더 선택하기"를 안내한다.
                vm.load();
                ToastPresenter.show(binding.getRoot(),
                        getString(R.string.select_permission_partial));
                break;
            case DENIED:
            default:
                // 안내를 먼저 띄우고, 사용자가 읽을 수 있게 설정 이동은 CTA 로 넘긴다.
                ToastPresenter.show(binding.getRoot(),
                        getString(R.string.select_permission_denied));
                binding.startAnalyzeButton.setOnClickListener(v -> openAppSettings());
                break;
        }
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
    }
}
