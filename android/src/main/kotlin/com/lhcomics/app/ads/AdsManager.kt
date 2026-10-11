package com.lhcomics.app.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.lhcomics.app.BuildConfig
import com.lhcomics.app.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 광고 정책: 읽는 화면에는 광고를 넣지 않는다(경쟁 앱의 최대 불만).
 * - 배너: 홈/탐색 화면 하단에만
 * - 전면: 책을 닫을 때, [MIN_BOOKS_BETWEEN]권마다 한 번, 최소 [MIN_INTERVAL_MS] 간격
 */
class AdsManager(private val context: Context, private val settings: Settings) {
    private val initialized = AtomicBoolean(false)
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    private var interstitial: InterstitialAd? = null
    private var loading = false
    private var booksSinceAd = 0
    private var lastShownAt = 0L

    /** 유럽 등 동의가 필요한 지역은 UMP 동의를 먼저 받고 광고 SDK를 초기화한다. */
    fun start(activity: Activity) {
        if (settings.adsRemoved.value) return
        val consent = UserMessagingPlatform.getConsentInformation(activity)
        consent.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    if (consent.canRequestAds()) initialize()
                }
            },
            { error -> Log.w(TAG, "consent update failed: ${error.message}") },
        )
        if (consent.canRequestAds()) initialize()
    }

    private fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        MobileAds.initialize(context) {
            _ready.value = true
            preload()
        }
    }

    private fun preload() {
        if (loading || interstitial != null || settings.adsRemoved.value || !_ready.value) return
        loading = true
        InterstitialAd.load(
            context,
            BuildConfig.ADMOB_INTERSTITIAL_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitial = ad
                    loading = false
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loading = false
                }
            },
        )
    }

    /** 뷰어를 닫을 때 호출한다. 조건이 맞을 때만 실제로 보여준다. */
    fun onBookClosed(activity: Activity) {
        if (settings.adsRemoved.value) return
        booksSinceAd++
        val ad = interstitial
        val now = System.currentTimeMillis()
        if (ad == null || booksSinceAd < MIN_BOOKS_BETWEEN || now - lastShownAt < MIN_INTERVAL_MS) {
            preload()
            return
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                interstitial = null
                preload()
            }
        }
        interstitial = null
        booksSinceAd = 0
        lastShownAt = now
        ad.show(activity)
    }

    companion object {
        private const val TAG = "AdsManager"
        const val MIN_BOOKS_BETWEEN = 3
        const val MIN_INTERVAL_MS = 5 * 60 * 1000L
    }
}
