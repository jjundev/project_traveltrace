package com.traveltrace.app.di;

import com.traveltrace.app.data.GeocoderStub;
import com.traveltrace.app.data.VisionProviderStub;
import com.traveltrace.app.data.repo.RoomAnalysisCacheStore;
import com.traveltrace.app.data.repo.RoomPhotoAnalysisRepository;
import com.traveltrace.app.data.repo.RoomTripRepository;
import com.traveltrace.app.domain.AnalysisCacheStore;
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
 * VisionProvider·Geocoder 는 S3(AI 인식)까지 스텁으로 남는다 — S1 은 EXIF GPS 만 쓴다.
 * 여행 저장/조회는 여기서 Room 구현으로 교체됐다.
 */
@Module
@InstallIn(SingletonComponent.class)
public abstract class AppModule {

    @Binds
    @Singleton
    public abstract VisionProvider bindVisionProvider(VisionProviderStub impl);

    @Binds
    @Singleton
    public abstract Geocoder bindGeocoder(GeocoderStub impl);

    @Binds
    @Singleton
    public abstract TripRepository bindTripRepository(RoomTripRepository impl);

    @Binds
    @Singleton
    public abstract PhotoAnalysisRepository bindPhotoAnalysisRepository(
            RoomPhotoAnalysisRepository impl);

    @Binds
    @Singleton
    public abstract AnalysisCacheStore bindAnalysisCacheStore(RoomAnalysisCacheStore impl);
}
