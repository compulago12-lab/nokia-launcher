package com.nokiaretro.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()

        webView = WebView(this).apply {
            setBackgroundColor(0xFF0C0D0F.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            webViewClient = WebViewClient()
            addJavascriptInterface(LauncherBridge(this@MainActivity), "AndroidLauncher")
        }

        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("window.onAndroidBack && window.onAndroidBack();", null)
            }
        })
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (::webView.isInitialized) {
            webView.evaluateJavascript("window.onAndroidResume && window.onAndroidResume();", null)
        }
    }

    inner class LauncherBridge(private val ctx: Context) {

        @JavascriptInterface
        fun getInstalledApps(): String {
            val pm = ctx.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(intent, 0)
            val arr = JSONArray()
            val seen = HashSet<String>()

            resolveInfos
                .sortedBy { it.loadLabel(pm).toString().lowercase() }
                .forEach { info ->
                    val pkg = info.activityInfo.packageName
                    if (pkg == ctx.packageName || seen.contains(pkg)) return@forEach
                    seen.add(pkg)
                    val label = info.loadLabel(pm).toString()
                    val iconData = drawableToBase64(info.loadIcon(pm))
                    val obj = JSONObject()
                    obj.put("id", pkg)
                    obj.put("pkg", pkg)
                    obj.put("name", label)
                    obj.put("icon", iconData)
                    arr.put(obj)
                }
            return arr.toString()
        }

        @JavascriptInterface
        fun launchApp(packageName: String) {
            runOnUiThread {
                try {
                    val intent = ctx.packageManager.getLaunchIntentForPackage(packageName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(intent)
                    }
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun dialNumber(number: String) {
            runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun openCamera() {
            runOnUiThread {
                try {
                    val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun openBrowser() {
            runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun openHomeSettings() {
            runOnUiThread {
                try {
                    val intent = Intent(Settings.ACTION_HOME_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun getBatteryLevel(): Int {
            return try {
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
            } catch (_: Exception) {
                100
            }
        }

        @JavascriptInterface
        fun vibrate(ms: Long) {
            try {
                val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(ms)
                }
            } catch (_: Exception) {}
        }

        private fun drawableToBase64(drawable: Drawable): String {
            return try {
                val size = 84
                val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
                    Bitmap.createScaledBitmap(drawable.bitmap, size, size, true)
                } else {
                    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    drawable.setBounds(0, 0, size, size)
                    drawable.draw(canvas)
                    bmp
                }
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 85, stream)
                "data:image/png;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            } catch (_: Exception) {
                ""
            }
        }
    }
}
