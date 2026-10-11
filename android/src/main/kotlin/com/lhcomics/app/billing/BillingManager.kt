package com.lhcomics.app.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.lhcomics.app.BuildConfig
import com.lhcomics.app.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 광고 제거 1회 결제(구독 아님). Play Console에 [BuildConfig.REMOVE_ADS_PRODUCT_ID]
 * 인앱 상품을 등록해야 실제로 동작한다.
 */
class BillingManager(context: Context, private val settings: Settings) {
    private val _price = MutableStateFlow<String?>(null)
    val price: StateFlow<String?> = _price

    private var product: ProductDetails? = null

    private val client = BillingClient.newBuilder(context)
        .setListener { result, purchases -> if (result.ok) purchases?.forEach(::handle) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    fun connect() {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (!result.ok) {
                    Log.w(TAG, "billing unavailable: ${result.debugMessage}")
                    return
                }
                restore()
                queryProduct()
            }

            override fun onBillingServiceDisconnected() {}
        })
    }

    private fun restore() {
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
        ) { result, purchases ->
            if (!result.ok) return@queryPurchasesAsync
            val owned = purchases.any { it.isRemoveAds && it.purchaseState == Purchase.PurchaseState.PURCHASED }
            settings.setAdsRemoved(owned)
            purchases.forEach(::handle)
        }
    }

    private fun queryProduct() {
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(BuildConfig.REMOVE_ADS_PRODUCT_ID)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            )
        ).build()
        client.queryProductDetailsAsync(params) { result, details ->
            if (!result.ok) return@queryProductDetailsAsync
            product = details.productDetailsList.firstOrNull()
            _price.value = product?.oneTimePurchaseOfferDetails?.formattedPrice
        }
    }

    /** @return 상품 정보를 아직 못 받았으면 false. */
    fun purchase(activity: Activity): Boolean {
        val details = product ?: return false
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(
            listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).build())
        ).build()
        client.launchBillingFlow(activity, params)
        return true
    }

    private fun handle(purchase: Purchase) {
        if (!purchase.isRemoveAds || purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        settings.setAdsRemoved(true)
        if (!purchase.isAcknowledged) {
            client.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build(),
            ) {}
        }
    }

    private val Purchase.isRemoveAds get() = BuildConfig.REMOVE_ADS_PRODUCT_ID in products
    private val BillingResult.ok get() = responseCode == BillingClient.BillingResponseCode.OK

    companion object {
        private const val TAG = "BillingManager"
    }
}
