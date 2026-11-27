package com.example.secureapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import org.json.JSONArray
import org.json.JSONObject
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private var pendingDownload: Triple<ByteArray, String, String>? = null
    private val REQ_STORAGE_PERMISSION = 1001
    private var adContainer: FrameLayout? = null
    private var bannerAdView: AdView? = null
    private var interstitialAd: InterstitialAd? = null
    @Volatile private var isAdFreeUser: Boolean = false

    private fun initAdMob() {
        MobileAds.initialize(this) {}
        loadInterstitialAd()
    }

    private fun loadBannerAd() {
        if (isAdFreeUser || bannerAdView != null) return
        val container = adContainer ?: return
        val adView = AdView(this).apply {
            adUnitId = "ca-app-pub-3940256099942544/6300978111"
            setAdSize(AdSize.BANNER)
        }
        container.removeAllViews()
        container.addView(adView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))
        container.visibility = View.VISIBLE
        adView.loadAd(AdRequest.Builder().build())
        bannerAdView = adView
    }

    private fun loadInterstitialAd() {
        if (isAdFreeUser) return
        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(
            this,
            "ca-app-pub-3940256099942544/1033173712",
            adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                }
                override fun onAdFailedToLoad(err: LoadAdError) {
                    interstitialAd = null
                }
            }
        )
    }

    private fun applyAdFreeState(adFree: Boolean) {
        isAdFreeUser = adFree
        runOnUiThread {
            if (adFree) {
                adContainer?.visibility = View.GONE
                bannerAdView?.pause()
                bannerAdView?.destroy()
                bannerAdView = null
                adContainer?.removeAllViews()
                interstitialAd = null
            } else {
                loadBannerAd()
                if (interstitialAd == null) loadInterstitialAd()
            }
        }
    }

    private inner class AndroidAdsBridge {
        @JavascriptInterface
        fun setAdFree(adFree: Boolean) {
            applyAdFreeState(adFree)
        }

        @JavascriptInterface
        fun showBanner() {
            applyAdFreeState(false)
        }

        @JavascriptInterface
        fun hideBanner() {
            applyAdFreeState(true)
        }

        @JavascriptInterface
        fun showInterstitial() {
            if (isAdFreeUser) return
            runOnUiThread {
                val ad = interstitialAd
                if (ad != null) {
                    interstitialAd = null
                    ad.show(this@MainActivity)
                    loadInterstitialAd()
                } else {
                    loadInterstitialAd()
                }
            }
        }
    }
    private var billingClient: BillingClient? = null
    @Volatile private var billingReady: Boolean = false
    private lateinit var mainWebView: WebView

    private fun dispatchBillingEvent(eventType: String, payload: JSONObject) {
        val wrapper = JSONObject().apply {
            put("type", eventType)
            put("data", payload)
        }
        val escaped = JSONObject.quote(wrapper.toString())
        val js = "try{var d=JSON.parse(" + escaped + ");window.__onAndroidBillingEvent&&window.__onAndroidBillingEvent(d);window.dispatchEvent(new CustomEvent('android-billing',{detail:d}));}catch(e){}"
        runOnUiThread {
            if (this::mainWebView.isInitialized) {
                mainWebView.evaluateJavascript(js, null)
            }
        }
    }

    private fun purchaseToJson(p: Purchase): JSONObject = JSONObject().apply {
        put("orderId", p.orderId ?: "")
        put("purchaseToken", p.purchaseToken)
        put("products", JSONArray(p.products))
        put("purchaseState", p.purchaseState)
        put("isAcknowledged", p.isAcknowledged)
        put("purchaseTime", p.purchaseTime)
        put("quantity", p.quantity)
    }

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                handlePurchase(purchase, consumeIfConsumable = pendingConsumableProducts.containsAll(purchase.products))
            }
        } else {
            val err = JSONObject().apply {
                put("responseCode", billingResult.responseCode)
                put("debugMessage", billingResult.debugMessage)
                put("canceled", billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED)
            }
            dispatchBillingEvent("purchase_error", err)
        }
    }

    private val pendingConsumableProducts = mutableSetOf<String>()

    private fun initBillingClient() {
        if (billingClient != null) return
        billingClient = BillingClient.newBuilder(this)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build()
            )
            .enableAutoServiceReconnection()
            .build()

        billingClient?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                billingReady = (billingResult.responseCode == BillingClient.BillingResponseCode.OK)
                dispatchBillingEvent("billing_ready", JSONObject().apply {
                    put("ready", billingReady)
                    put("responseCode", billingResult.responseCode)
                })
                if (billingReady) {
                    restoreAllPurchases(silent = true)
                }
            }

            override fun onBillingServiceDisconnected() {
                billingReady = false
            }
        })
    }

    private fun handlePurchase(purchase: Purchase, consumeIfConsumable: Boolean = false) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            if (consumeIfConsumable) {
                val consumeParams = ConsumeParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                billingClient?.consumeAsync(consumeParams) { br, _ ->
                    if (br.responseCode == BillingClient.BillingResponseCode.OK) {
                        pendingConsumableProducts.removeAll(purchase.products.toSet())
                        dispatchBillingEvent("purchase_success", purchaseToJson(purchase))
                    } else {
                        dispatchBillingEvent("purchase_error", JSONObject().apply {
                            put("responseCode", br.responseCode)
                            put("debugMessage", br.debugMessage)
                        })
                    }
                }
            } else if (!purchase.isAcknowledged) {
                val ackParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                billingClient?.acknowledgePurchase(ackParams) { br ->
                    if (br.responseCode == BillingClient.BillingResponseCode.OK) {
                        dispatchBillingEvent("purchase_success", purchaseToJson(purchase))
                    } else {
                        dispatchBillingEvent("purchase_error", JSONObject().apply {
                            put("responseCode", br.responseCode)
                            put("debugMessage", br.debugMessage)
                        })
                    }
                }
            } else {
                dispatchBillingEvent("purchase_success", purchaseToJson(purchase))
            }
        } else if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            dispatchBillingEvent("purchase_pending", purchaseToJson(purchase))
        }
    }

    private fun launchProductPurchase(productId: String, productType: String, consumable: Boolean) {
        val client = billingClient
        if (client == null || !billingReady) {
            initBillingClient()
            dispatchBillingEvent("purchase_error", JSONObject().apply {
                put("responseCode", BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)
                put("debugMessage", "Google Play Billing 연결 준비 중입니다.")
            })
            return
        }
        if (consumable) pendingConsumableProducts.add(productId) else pendingConsumableProducts.remove(productId)

        val typeConst = if (productType.equals("subs", ignoreCase = true)) {
            BillingClient.ProductType.SUBS
        } else {
            BillingClient.ProductType.INAPP
        }

        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(typeConst)
                .build()
        )
        val params = QueryProductDetailsParams.newBuilder().setProductList(productList).build()
        client.queryProductDetailsAsync(params) { billingResult, queryResult ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                dispatchBillingEvent("purchase_error", JSONObject().apply {
                    put("responseCode", billingResult.responseCode)
                    put("debugMessage", billingResult.debugMessage)
                })
                return@queryProductDetailsAsync
            }
            val details: ProductDetails? = queryResult.productDetailsList.firstOrNull()
            if (details == null) {
                dispatchBillingEvent("purchase_error", JSONObject().apply {
                    put("responseCode", BillingClient.BillingResponseCode.ITEM_UNAVAILABLE)
                    put("debugMessage", "상품을 찾을 수 없습니다: $productId")
                })
                return@queryProductDetailsAsync
            }
            val offerToken = if (typeConst == BillingClient.ProductType.SUBS) {
                details.subscriptionOfferDetails?.firstOrNull()?.offerToken
            } else {
                details.oneTimePurchaseOfferDetailsList?.firstOrNull()?.offerToken
            }
            val pdParamsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
            if (!offerToken.isNullOrBlank()) {
                pdParamsBuilder.setOfferToken(offerToken)
            }
            val flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(pdParamsBuilder.build()))
                .build()
            runOnUiThread {
                client.launchBillingFlow(this@MainActivity, flowParams)
            }
        }
    }

    private fun restoreAllPurchases(silent: Boolean = false) {
        val client = billingClient ?: return
        val restored = JSONArray()
        val inappParams = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryPurchasesAsync(inappParams) { brInapp, inappList ->
            if (brInapp.responseCode == BillingClient.BillingResponseCode.OK) {
                for (p in inappList) {
                    if (p.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        if (!p.isAcknowledged) handlePurchase(p, consumeIfConsumable = false)
                        restored.put(purchaseToJson(p))
                    }
                }
            }
            val subsParams = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
            client.queryPurchasesAsync(subsParams) { brSubs, subsList ->
                if (brSubs.responseCode == BillingClient.BillingResponseCode.OK) {
                    for (p in subsList) {
                        if (p.purchaseState == Purchase.PurchaseState.PURCHASED) {
                            if (!p.isAcknowledged) handlePurchase(p, consumeIfConsumable = false)
                            restored.put(purchaseToJson(p))
                        }
                    }
                }
                if (!silent || restored.length() > 0) {
                    dispatchBillingEvent("purchases_restored", JSONObject().apply {
                        put("purchases", restored)
                        put("count", restored.length())
                    })
                }
            }
        }
    }

    private inner class AndroidBillingBridge {
        @JavascriptInterface
        fun isReady(): Boolean = billingReady

        @JavascriptInterface
        fun purchase(productId: String) {
            launchProductPurchase(productId, "inapp", consumable = false)
        }

        @JavascriptInterface
        fun purchase(productId: String, productType: String) {
            launchProductPurchase(productId, productType, consumable = false)
        }

        @JavascriptInterface
        fun purchaseConsumable(productId: String) {
            launchProductPurchase(productId, "inapp", consumable = true)
        }

        @JavascriptInterface
        fun restorePurchases() {
            if (!billingReady) {
                initBillingClient()
            } else {
                restoreAllPurchases(silent = false)
            }
        }

        @JavascriptInterface
        fun queryProducts(productIdsCsv: String, productType: String) {
            val client = billingClient ?: return
            val ids = productIdsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (ids.isEmpty()) return
            val typeConst = if (productType.equals("subs", ignoreCase = true)) {
                BillingClient.ProductType.SUBS
            } else {
                BillingClient.ProductType.INAPP
            }
            val products = ids.map {
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(it)
                    .setProductType(typeConst)
                    .build()
            }
            val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()
            client.queryProductDetailsAsync(params) { br, res ->
                val arr = JSONArray()
                if (br.responseCode == BillingClient.BillingResponseCode.OK) {
                    for (d in res.productDetailsList) {
                        val price = if (typeConst == BillingClient.ProductType.SUBS) {
                            d.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice ?: ""
                        } else {
                            d.oneTimePurchaseOfferDetailsList?.firstOrNull()?.formattedPrice ?: ""
                        }
                        arr.put(JSONObject().apply {
                            put("productId", d.productId)
                            put("title", d.title)
                            put("description", d.description)
                            put("formattedPrice", price)
                        })
                    }
                }
                dispatchBillingEvent("products_loaded", JSONObject().apply {
                    put("responseCode", br.responseCode)
                    put("products", arr)
                })
            }
        }
    }


    private inner class AndroidDownloadBridge {
        @JavascriptInterface
        fun saveBase64File(base64Data: String, fileName: String, mimeType: String) {
            try {
                val cleanBase64 = if (base64Data.contains(",")) {
                    base64Data.substringAfter(",")
                } else {
                    base64Data
                }
                val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                runOnUiThread {
                    saveBytesToDownloads(bytes, fileName, mimeType)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "파일 저장 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun inferMimeType(fileName: String, rawMime: String): String {
        if (rawMime.isNotBlank() && rawMime != "application/octet-stream") {
            return rawMime
        }
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "xls" -> "application/vnd.ms-excel"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "zip" -> "application/zip"
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "html", "htm" -> "text/html"
            "txt" -> "text/plain"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    private fun sanitizeFileName(rawName: String, mimeType: String): String {
        val base = File(rawName.ifBlank { "download_${System.currentTimeMillis()}" }).name
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (base.contains('.')) return base
        val ext = when {
            mimeType.contains("spreadsheetml") -> "xlsx"
            mimeType.contains("csv") -> "csv"
            mimeType.contains("json") -> "json"
            mimeType.contains("zip") -> "zip"
            mimeType.contains("pdf") -> "pdf"
            mimeType.contains("jpeg") -> "jpg"
            mimeType.contains("png") -> "png"
            else -> "bin"
        }
        return "$base.$ext"
    }

    private fun saveBytesToDownloads(bytes: ByteArray, rawName: String, rawMime: String) {
        val mime = inferMimeType(rawName, rawMime)
        val safeName = sanitizeFileName(rawName, mime)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    Toast.makeText(this, "다운로드 폴더에 저장됨: $safeName", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "파일 저장 경로 생성 실패", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "저장 오류: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingDownload = Triple(bytes, safeName, mime)
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQ_STORAGE_PERMISSION
                )
                return
            }
            writeLegacyDownloadFile(bytes, safeName, mime)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeLegacyDownloadFile(bytes: ByteArray, safeName: String, mime: String) {
        try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            var target = File(dir, safeName)
            var idx = 1
            val stem = safeName.substringBeforeLast('.', safeName)
            val ext = safeName.substringAfterLast('.', "")
            while (target.exists()) {
                val candidate = if (ext.isNotEmpty()) "$stem($idx).$ext" else "$stem($idx)"
                target = File(dir, candidate)
                idx++
            }
            FileOutputStream(target).use { it.write(bytes) }
            MediaScannerConnection.scanFile(this, arrayOf(target.absolutePath), arrayOf(mime), null)
            Toast.makeText(this, "다운로드 폴더에 저장됨: ${target.name}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "저장 오류: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE_PERMISSION) {
            val pending = pendingDownload
            pendingDownload = null
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED && pending != null) {
                writeLegacyDownloadFile(pending.first, pending.second, pending.third)
            } else {
                Toast.makeText(this, "저장소 권한이 거부되어 파일을 저장할 수 없습니다.", Toast.LENGTH_LONG).show()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webView = WebView(this)
        mainWebView = webView
        initBillingClient()
        initAdMob()
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val webParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
        val adBox = FrameLayout(this).apply {
            visibility = View.GONE
        }
        adContainer = adBox
        rootLayout.addView(webView, webParams)
        rootLayout.addView(adBox, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        setContentView(rootLayout)

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }

        WebView.setWebContentsDebuggingEnabled(false)

        webView.addJavascriptInterface(AndroidDownloadBridge(), "AndroidDownloader")
        webView.addJavascriptInterface(AndroidBillingBridge(), "AndroidBilling")
        webView.addJavascriptInterface(AndroidAdsBridge(), "AndroidAds")

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            if (url.startsWith("data:")) {
                val name = URLUtil.guessFileName(url, contentDisposition, mimetype)
                val cleanB64 = url.substringAfter(",")
                try {
                    val bytes = Base64.decode(cleanB64, Base64.DEFAULT)
                    saveBytesToDownloads(bytes, name, mimetype)
                } catch (e: Exception) {
                    Toast.makeText(this, "데이터 다운로드 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } else if (url.startsWith("blob:")) {
                val guessedName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                    .replace("'", "")
                val safeMime = mimetype.replace("'", "")
                val fetchJs = "(function(){fetch('" + url + "').then(function(r){return r.blob();}).then(function(b){var fr=new FileReader();fr.onloadend=function(){if(window.AndroidDownloader)window.AndroidDownloader.saveBase64File(fr.result,'" + guessedName + "',b.type||'" + safeMime + "');};fr.readAsDataURL(b);});})();"
                webView.evaluateJavascript(fetchJs, null)
            } else if (url.startsWith("http://") || url.startsWith("https://")) {
                try {
                    val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                    val request = DownloadManager.Request(Uri.parse(url)).apply {
                        setMimeType(mimetype)
                        addRequestHeader("User-Agent", userAgent)
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    }
                    val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    dm.enqueue(request)
                    Toast.makeText(this, "다운로드 시작: $fileName", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "다운로드 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript("(function(){\n  if (!window.AndroidDownloader) return;\n  if (!window.__spBlobMap) window.__spBlobMap = new Map();\n  if (!window.__spUrlHooked) {\n    window.__spUrlHooked = true;\n    var origCreate = URL.createObjectURL.bind(URL);\n    var origRevoke = URL.revokeObjectURL.bind(URL);\n    URL.createObjectURL = function(obj) {\n      var u = origCreate(obj);\n      if (obj instanceof Blob) { window.__spBlobMap.set(u, obj); }\n      return u;\n    };\n    URL.revokeObjectURL = function(u) {\n      setTimeout(function() {\n        try { window.__spBlobMap.delete(u); } catch(e) {}\n        try { origRevoke(u); } catch(e) {}\n      }, 15000);\n    };\n  }\n  function sendBlobToAndroid(blob, fileName, fallbackMime) {\n    var reader = new FileReader();\n    reader.onloadend = function() {\n      var res = typeof reader.result === \"string\" ? reader.result : \"\";\n      var mime = blob.type || fallbackMime || \"application/octet-stream\";\n      window.AndroidDownloader.saveBase64File(res, fileName || \"download\", mime);\n    };\n    reader.readAsDataURL(blob);\n  }\n  function handleAnchorDownload(anchor) {\n    if (!anchor) return false;\n    var href = anchor.getAttribute(\"href\") || anchor.href || \"\";\n    var hasDl = anchor.hasAttribute(\"download\");\n    if (!href) return false;\n    if (!hasDl && href.indexOf(\"blob:\") !== 0 && href.indexOf(\"data:\") !== 0) return false;\n    var fileName = anchor.getAttribute(\"download\") || anchor.download || \"\";\n    if (href.indexOf(\"data:\") === 0) {\n      var semi = href.indexOf(\";\");\n      var mime = semi > 5 ? href.slice(5, semi) : \"application/octet-stream\";\n      window.AndroidDownloader.saveBase64File(href, fileName || \"download\", mime);\n      return true;\n    }\n    if (href.indexOf(\"blob:\") === 0) {\n      var cached = window.__spBlobMap && window.__spBlobMap.get(href);\n      if (cached) {\n        sendBlobToAndroid(cached, fileName, cached.type);\n        return true;\n      }\n      fetch(href).then(function(r) { return r.blob(); }).then(function(b) {\n        sendBlobToAndroid(b, fileName, b.type);\n      }).catch(function() {});\n      return true;\n    }\n    return false;\n  }\n  window.__spInstallDownloadHook = function() {\n    if (!HTMLAnchorElement.prototype.__spClickHooked) {\n      HTMLAnchorElement.prototype.__spClickHooked = true;\n      var origClick = HTMLAnchorElement.prototype.click;\n      HTMLAnchorElement.prototype.click = function() {\n        if (handleAnchorDownload(this)) return;\n        return origClick.apply(this, arguments);\n      };\n    }\n    if (!document.__spDlListener) {\n      document.__spDlListener = true;\n      document.addEventListener(\"click\", function(ev) {\n        var a = ev.target && ev.target.closest ? ev.target.closest(\"a\") : null;\n        if (a && handleAnchorDownload(a)) {\n          ev.preventDefault();\n          ev.stopPropagation();\n        }\n      }, true);\n    }\n  };\n  if (!document.__spOpenHooked) {\n    document.__spOpenHooked = true;\n    var origClose = document.close.bind(document);\n    document.close = function() {\n      var r = origClose.apply(document, arguments);\n      setTimeout(function() {\n        if (window.__spInstallDownloadHook) window.__spInstallDownloadHook();\n      }, 0);\n      return r;\n    };\n  }\n  window.__spInstallDownloadHook();\n})();", null)
            }
        }

        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    override fun onResume() {
        super.onResume()
        bannerAdView?.resume()
        if (billingReady) {
            restoreAllPurchases(silent = true)
        }
    }

    override fun onPause() {
        bannerAdView?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        bannerAdView?.destroy()
        billingClient?.endConnection()
        super.onDestroy()
    }
}