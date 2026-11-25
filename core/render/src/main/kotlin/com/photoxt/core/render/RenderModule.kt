package com.photoxt.core.render

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RenderModule {
    @Provides
    @Singleton
    fun assets(@ApplicationContext context: Context): EditAssets =
        EditAssets(File(context.filesDir, "edits/assets"))

    @Provides
    @Singleton
    fun exporter(@ApplicationContext context: Context, assets: EditAssets): PhotoExporter =
        GlPhotoExporter(context, assets)

    @Provides
    @Singleton
    fun collageExporter(@ApplicationContext context: Context, assets: EditAssets): CollageExporter =
        CanvasCollageExporter(context, assets)
}
