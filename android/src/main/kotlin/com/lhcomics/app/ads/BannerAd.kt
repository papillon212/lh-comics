package com.lhcomics.app.ads

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.lhcomics.app.BuildConfig
import com.lhcomics.app.app

/** 화면 너비에 맞춘 적응형 배너. 광고 제거 구매 시 아무것도 그리지 않는다. */
@Composable
fun BannerAd(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.app
    val removed by app.settings.adsRemoved.collectAsStateWithLifecycle()
    val ready by app.ads.ready.collectAsStateWithLifecycle()
    if (removed || !ready) return

    val width = LocalConfiguration.current.screenWidthDp
    val adView = remember(width) {
        AdView(context).apply {
            adUnitId = BuildConfig.ADMOB_BANNER_ID
            setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, width))
            loadAd(AdRequest.Builder().build())
        }
    }
    DisposableEffect(adView) { onDispose { adView.destroy() } }
    // 하단 바는 시스템 내비게이션 영역을 피해야 제스처 핸들과 겹치지 않는다.
    AndroidView(factory = { adView }, modifier = modifier.fillMaxWidth().navigationBarsPadding())
}
