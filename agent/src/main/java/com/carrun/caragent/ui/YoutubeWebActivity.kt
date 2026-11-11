package com.carrun.caragent.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.carrun.caragent.databinding.ActivityYoutubeWebBinding

class YoutubeWebActivity : AppCompatActivity() {
    private lateinit var binding: ActivityYoutubeWebBinding

    interface Controller {
        fun skipAd()
        fun seekTo(seconds: Int)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityYoutubeWebBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val query = intent.getStringExtra(EXTRA_QUERY).orEmpty().ifBlank { "music" }
        binding.webView.settings.javaScriptEnabled = true
        binding.webView.settings.domStorageEnabled = true
        binding.webView.webViewClient = WebViewClient()
        binding.webView.webChromeClient = WebChromeClient()
        binding.webView.loadUrl("https://m.youtube.com/results?search_query=${android.net.Uri.encode(query)}")

        controller = object : Controller {
            override fun skipAd() {
                binding.webView.evaluateJavascript(
                    """
                    (function(){
                      var b = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
                      if (b) { b.click(); return 'skipped'; }
                      return 'no-skip-button';
                    })();
                    """.trimIndent(),
                    null,
                )
            }

            override fun seekTo(seconds: Int) {
                binding.webView.evaluateJavascript(
                    """
                    (function(){
                      var v = document.querySelector('video');
                      if (v) { v.currentTime = $seconds; return 'seeked'; }
                      return 'no-video';
                    })();
                    """.trimIndent(),
                    null,
                )
            }
        }
    }

    override fun onDestroy() {
        if (controller != null) controller = null
        binding.webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_QUERY = "query"
        @Volatile
        var controller: Controller? = null
    }
}
