package com.photoxt.core.ml

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal interface MlModule {
    @Binds
    fun detectors(detectors: MlKitDetectors): PhotoDetectors

    @Binds
    fun inpainter(inpainter: LiteRtInpainter): Inpainter
}
