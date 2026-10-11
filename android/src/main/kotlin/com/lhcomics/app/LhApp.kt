package com.lhcomics.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import com.lhcomics.app.ads.AdsManager
import com.lhcomics.app.billing.BillingManager
import com.lhcomics.app.data.LibraryManager
import com.lhcomics.app.data.ProgressStore
import com.lhcomics.app.data.Settings
import com.lhcomics.app.data.SourceStore
import com.lhcomics.app.image.CoverFetcher
import com.lhcomics.app.image.ImageKeyer
import com.lhcomics.app.image.PageFetcher

class LhApp : Application(), SingletonImageLoader.Factory {
    lateinit var sources: SourceStore
        private set
    lateinit var progress: ProgressStore
        private set
    lateinit var settings: Settings
        private set
    lateinit var libraries: LibraryManager
        private set
    lateinit var ads: AdsManager
        private set
    lateinit var billing: BillingManager
        private set

    override fun onCreate() {
        super.onCreate()
        sources = SourceStore(this)
        progress = ProgressStore(this)
        settings = Settings(this)
        libraries = LibraryManager(this, sources)
        ads = AdsManager(this, settings)
        billing = BillingManager(this, settings).also { it.connect() }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(PageFetcher.Factory(libraries))
                add(CoverFetcher.Factory(this@LhApp, libraries))
                add(ImageKeyer.Page)
                add(ImageKeyer.Cover)
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.3).build() }
            .build()
}
