package com.traveltrace.app.ui.photo;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.traveltrace.app.ui.preview.ScreenFixtures;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

/** 화면-우선 단계: ScreenFixtures 공급. 로직 에픽에서 MediaStore 조회로 교체된다. */
@HiltViewModel
public class PhotoSelectionViewModel extends ViewModel {

    private final MutableLiveData<PhotoSelectionUiState> state = new MutableLiveData<>();

    @Inject
    public PhotoSelectionViewModel() {
        state.setValue(ScreenFixtures.photoSelection());
    }

    public LiveData<PhotoSelectionUiState> state() {
        return state;
    }

    /** 타일 선택 토글 — 선택 상태는 화면 디자인의 일부다. */
    public void toggle(int index) {
        PhotoSelectionUiState current = state.getValue();
        if (current == null) return;
        state.setValue(current.withToggled(index));
    }
}
