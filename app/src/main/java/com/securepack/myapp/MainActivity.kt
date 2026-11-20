package com.securepack.myapp

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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private var pendingDownload: Triple<ByteArray, String, String>? = null
    private val REQ_STORAGE_PERMISSION = 1001

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
        setContentView(webView)

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
}