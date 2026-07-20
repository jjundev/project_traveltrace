package com.traveltrace.app.di;

import com.traveltrace.app.data.geocode.RoutingGeocoder;
import com.traveltrace.app.data.repo.RoomPhotoAnalysisRepository;
import com.traveltrace.app.data.repo.RoomTripRepository;
import com.traveltrace.app.data.vision.VertexGeminiProvider;
import com.traveltrace.app.domain.Geocoder;
import com.traveltrace.app.domain.PhotoAnalysisRepository;
import com.traveltrace.app.domain.TripRepository;
import com.traveltrace.app.domain.VisionProvider;

import javax.inject.Singleton;

import dagger.Binds;
import dagger.Module;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;

/**
 * S3 에서 VisionProvider·Geocoder 가 스텁을 벗고 실구현이 됐다. OpenAI 폴백 구현이
 * 들어오는 S5 에서는 여기가 아니라 VisionProvider 뒤의 합성 구현이 바뀐다 — 상위
 * 계층은 provider 종류를 계속 모른다.
 */
@Module
@InstallIn(SingletonComponent.class)
public abstract class AppModule {

    @Binds
    @Singleton
    public abstract VisionProvider bindVisionProvider(VertexGeminiProvider impl);

    @Binds
    @Singleton
    public abstract Geocoder bindGeocoder(RoutingGeocoder impl);

    @Binds
    @Singleton
    public abstract TripRepository bindTripRepository(RoomTripRepository impl);

    @Binds
    @Singleton
    public abstract PhotoAnalysisRepository bindPhotoAnalysisRepository(
            RoomPhotoAnalysisRepository impl);
}
