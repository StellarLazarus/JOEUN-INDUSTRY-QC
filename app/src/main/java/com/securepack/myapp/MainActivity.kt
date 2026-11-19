package com.securepack.myapp

import android.annotation.SuppressLint
import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    inner class AndroidDownloader {
        @JavascriptInterface
        fun saveBase64File(base64Data: String, fileName: String, mimeType: String) {
            try {
                val cleanBase64 = base64Data.substringAfter(",")
                val fileBytes = Base64.decode(cleanBase64, Base64.DEFAULT)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    uri?.let {
                        contentResolver.openOutputStream(it)?.use { out -> out.write(fileBytes) }
                    }
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!dir.exists()) dir.mkdirs()
                    FileOutputStream(File(dir, fileName)).use { out -> out.write(fileBytes) }
                }

                runOnUiThread {
                    Toast.makeText(this@MainActivity, "다운로드 폴더 저장 완료: $fileName", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "파일 저장 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
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
        webView.addJavascriptInterface(AndroidDownloader(), "AndroidDownloader")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }
        }

        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }
}
2. 적용 및 확인 절차
MainActivity.kt만 고치면 안드로이드 앱 쪽 통로만 열린 상태이므로, index.html에서도 이 통로(window.AndroidDownloader)를 호출하도록 아래 순서까지 함께 적용해야 실제로 파일이 저장됩니다.

MainActivity.kt 커밋: 위 코드를 app/src/main/java/com/securepack/myapp/MainActivity.kt에 붙여넣고 Commit changes를 누릅니다.

index.html 다운로드 연결 스크립트 삽입: app/src/main/assets/index.html 파일을 열고 </head> 태그 바로 위에 아래 스크립트를 붙여넣은 뒤 Commit changes를 누릅니다. (기존 XLSX.writeFile이나 blob: 다운로드 링크 클릭을 자동으로 가로채서 AndroidDownloader로 넘겨줍니다.)

HTML
<script>
  (function() {
    if (!window.AndroidDownloader) return;
    const origClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function() {
      if (this.download && this.href && (this.href.startsWith('blob:') || this.href.startsWith('data:'))) {
        const fileName = this.download;
        fetch(this.href)
          .then(res => res.blob())
          .then(blob => {
            const reader = new FileReader();
            reader.onloadend = () => {
              window.AndroidDownloader.saveBase64File(
                reader.result,
                fileName,
                blob.type || 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
              );
            };
            reader.readAsDataURL(blob);
          });
        return;
      }
      return origClick.apply(this, arguments);
    };
  })();
</script>
