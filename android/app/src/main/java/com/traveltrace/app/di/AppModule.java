package com.traveltrace.app.di;

import com.traveltrace.app.data.GeocoderStub;
import com.traveltrace.app.data.TripRepositoryStub;
import com.traveltrace.app.data.VisionProviderStub;
import com.traveltrace.app.domain.Geocoder;
import com.traveltrace.app.domain.TripRepository;
import com.traveltrace.app.domain.VisionProvider;

import javax.inject.Singleton;

import dagger.Binds;
import dagger.Module;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;

/**
 * A1: 핵심 인터페이스(VisionProvider/Geocoder/TripRepository)의 빈 스텁을 Hilt로 바인딩.
 * 각 에픽(D/E/I)에서 스텁 바인딩을 실제 구현으로 교체한다.
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
    public abstract TripRepository bindTripRepository(TripRepositoryStub impl);
}
