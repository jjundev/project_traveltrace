package com.traveltrace.app.ui.map;

import androidx.lifecycle.ViewModel;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

/** A1 골격용 빈 ViewModel. 지도/리플레이는 Epic J/K에서 구현. */
@HiltViewModel
public class MapReplayViewModel extends ViewModel {

    @Inject
    public MapReplayViewModel() {
    }
}
