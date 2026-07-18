package com.traveltrace.app.ui.photo;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.FragmentPhotoSelectionBinding;

import dagger.hilt.android.AndroidEntryPoint;

/** SELECT: 기간 카드 + 3열 선택 그리드. */
@AndroidEntryPoint
public class PhotoSelectionFragment extends Fragment {

    private FragmentPhotoSelectionBinding binding;

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
        PhotoSelectionViewModel vm = new ViewModelProvider(this).get(PhotoSelectionViewModel.class);

        binding.selectBack.setOnClickListener(v ->
                NavHostFragment.findNavController(this).popBackStack());
        binding.startAnalyzeButton.setOnClickListener(v ->
                NavHostFragment.findNavController(this).navigate(R.id.action_photo_to_analysis));

        vm.state().observe(getViewLifecycleOwner(), state ->
                PhotoSelectionRenderer.render(binding, state, vm::toggle));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
