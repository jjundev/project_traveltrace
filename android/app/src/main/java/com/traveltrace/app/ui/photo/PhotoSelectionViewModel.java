package com.traveltrace.app.ui.photo;

import androidx.lifecycle.ViewModel;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

/** A1 골격용 빈 ViewModel. 사진 선택 로직은 Epic B에서 구현. */
@HiltViewModel
public class PhotoSelectionViewModel extends ViewModel {

    @Inject
    public PhotoSelectionViewModel() {
    }
}
