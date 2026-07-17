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
        // ViewModel은 Hilt로 주입됨 (A1 골격에선 미사용).
        new ViewModelProvider(this).get(PhotoSelectionViewModel.class);
        binding.nextButton.setOnClickListener(v ->
                NavHostFragment.findNavController(this).navigate(R.id.action_photo_to_analysis));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
