package com.freshly.premier

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.ContentValues
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.print.PrintJob
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.webkit.WebChromeClient
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.activity.OnBackPressedCallback
import androidx.webkit.WebViewAssetLoader
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import android.util.Base64

class MainActivity : FragmentActivity() {
    private lateinit var webView: WebView
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val chooserRequest = 4102
    private val prefs by lazy { getSharedPreferences("freshly_security", MODE_PRIVATE) }
    private var hasOpenedHome = false
    @Volatile private var cashierPageActive = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activePrintJob: PrintJob? = null
    private var printMonitor: Runnable? = null
    private var notificationPermissionCheckedThisLaunch = false
    private val notificationPermissionRequestCode = 7101
    private val notificationChannelId = "freshly_orders"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(12, 14, 19)
        window.navigationBarColor = android.graphics.Color.rgb(7, 8, 11)
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        webView = WebView(this)
        setContentView(webView)
        createFreshlyNotificationChannel()
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = true
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        webView.settings.setSupportMultipleWindows(false)
        webView.addJavascriptInterface(BackupBridge(), "FRESHLYNative")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                cashierPageActive = isTrustedCashierUrl(url)
            }
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                cashierPageActive = isTrustedCashierUrl(url)
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                assetLoader.shouldInterceptRequest(request.url)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val protected = uri.host == "appassets.androidplatform.net" &&
                    uri.path?.endsWith("/cashier.html") == true
                if (!protected) return false
                authenticateForEntry { webView.post { webView.loadUrl(uri.toString()) } }
                return true
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(webView: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = callback
                val intent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
                }
                return try { startActivityForResult(intent, chooserRequest); true }
                catch (_: Exception) { fileChooserCallback = null; false }
            }
        }
        if (!prefs.contains("password_hash")) {
            showPasswordSetup { hasOpenedHome = true; openHome() }
        } else if (prefs.getBoolean("password_enabled", true)) {
            authenticateForEntry { hasOpenedHome = true; openHome() }
        } else {
            hasOpenedHome = true
            openHome()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (webView.canGoBack()) webView.goBack() else finish() }
        })
    }

    private fun isTrustedCashierUrl(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        return try {
            val uri = Uri.parse(rawUrl)
            uri.scheme == "https" &&
                uri.host == "appassets.androidplatform.net" &&
                uri.path == "/assets/cashier.html"
        } catch (_: Exception) { false }
    }

    private inner class BackupBridge {
        @JavascriptInterface
        fun saveBackup(json: String, requestedName: String): String {
            if (!cashierPageActive) return "ERROR"
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "UNSUPPORTED"
            val safeName = requestedName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                .ifBlank { "FRESHLY_POS_BACKUP.json" }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/FRESHLY")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return "ERROR"
            return try {
                val output = contentResolver.openOutputStream(uri) ?: throw IllegalStateException("Cannot open backup file")
                output.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                "OK"
            } catch (_: Exception) {
                contentResolver.delete(uri, null, null)
                "ERROR"
            }
        }
        @JavascriptInterface
        fun getPasswordEnabled(): Boolean {
            if (!cashierPageActive) return true
            return prefs.getBoolean("password_enabled", true)
        }

        @JavascriptInterface
        fun setPasswordEnabled(enabled: Boolean, confirmationPassword: String): Boolean {
            if (!cashierPageActive) return false
            if (!prefs.contains("password_hash") || !verifyPassword(confirmationPassword)) return false
            val saved = prefs.edit().putBoolean("password_enabled", enabled).commit()
            return saved && prefs.getBoolean("password_enabled", true) == enabled
        }

        @JavascriptInterface
        fun requestNotificationPermission() {
            runOnUiThread { requestNotificationPermissionIfNeeded() }
        }

        @JavascriptInterface
        fun openNotificationSettings() {
            runOnUiThread { openNotificationSettingsScreen() }
        }

        @JavascriptInterface
        fun areNotificationsEnabled(): Boolean = areFreshlyNotificationsEnabled()

        @JavascriptInterface
        fun showNotification(title: String, body: String, notificationTag: String) {
            runOnUiThread {
                if (!areFreshlyNotificationsEnabled()) return@runOnUiThread
                val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
                val pendingIntent = launchIntent?.let {
                    PendingIntent.getActivity(this@MainActivity, 7102, it, flags)
                }
                val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Notification.Builder(this@MainActivity, notificationChannelId)
                } else {
                    Notification.Builder(this@MainActivity)
                }
                builder.setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title.take(80))
                    .setContentText(body.take(180))
                    .setStyle(Notification.BigTextStyle().bigText(body.take(500)))
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_ALL)
                if (pendingIntent != null) builder.setContentIntent(pendingIntent)
                manager.notify(notificationTag.hashCode(), builder.build())
            }
        }

        @JavascriptInterface
        fun printReport(requestedTitle: String) {
            runOnUiThread {
                try {
                    val title = requestedTitle.take(100).ifBlank { "FRESHLY - التقرير المالي" }
                    val printManager = getSystemService(PRINT_SERVICE) as PrintManager
                    val adapter = webView.createPrintDocumentAdapter(title)
                    val attributes = PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape())
                        .setMinMargins(PrintAttributes.Margins(250, 250, 250, 250))
                        .build()
                    printMonitor?.let { mainHandler.removeCallbacks(it) }
                    activePrintJob = printManager.print(title, adapter, attributes)
                    val monitor = object : Runnable {
                        override fun run() {
                            val job = activePrintJob
                            if (job == null || job.isCompleted || job.isCancelled || job.isFailed) {
                                webView.evaluateJavascript(
                                    "window.__freshlyCleanupPrint && window.__freshlyCleanupPrint()", null
                                )
                                activePrintJob = null
                                printMonitor = null
                            } else {
                                mainHandler.postDelayed(this, 1000)
                            }
                        }
                    }
                    printMonitor = monitor
                    mainHandler.postDelayed(monitor, 1000)
                } catch (error: Exception) {
                    webView.evaluateJavascript(
                        "window.__freshlyCleanupPrint && window.__freshlyCleanupPrint()", null
                    )
                    Toast.makeText(
                        this@MainActivity,
                        "تعذر فتح نافذة الطباعة: ${error.localizedMessage ?: "خطأ غير معروف"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

    }

    private fun areFreshlyNotificationsEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = manager.getNotificationChannel(notificationChannelId) ?: return false
            if (channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (notificationPermissionCheckedThisLaunch) return
        notificationPermissionCheckedThisLaunch = true

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            val alreadyPrompted = prefs.getBoolean("notification_permission_prompted", false)
            if (!alreadyPrompted) {
                prefs.edit().putBoolean("notification_permission_prompted", true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationPermissionRequestCode)
            } else if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                AlertDialog.Builder(this)
                    .setTitle("السماح بإشعارات FRESHLY")
                    .setMessage("الإشعارات ضرورية لتنبيهك بالطلبات الجديدة والتنبيهات المهمة من التطبيق.")
                    .setPositiveButton("السماح") { _, _ ->
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationPermissionRequestCode)
                    }
                    .setNegativeButton("لاحقًا", null)
                    .setNeutralButton("إعدادات التطبيق") { _, _ -> openNotificationSettingsScreen() }
                    .show()
            } else {
                showNotificationSettingsDialog()
            }
            return
        }

        if (!areFreshlyNotificationsEnabled()) showNotificationSettingsDialog()
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == notificationPermissionRequestCode) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "تم السماح بإشعارات FRESHLY", Toast.LENGTH_SHORT).show()
            } else {
                showNotificationSettingsDialog()
            }
        }
    }

    private fun showNotificationSettingsDialog() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("إشعارات FRESHLY غير مفعّلة")
            .setMessage("لتصلك تنبيهات الطلبات، افتح إعدادات إشعارات التطبيق وفعّل السماح بالإشعارات وقناة إشعارات FRESHLY.")
            .setPositiveButton("فتح إعدادات الإشعارات") { _, _ -> openNotificationSettingsScreen() }
            .setNegativeButton("لاحقًا", null)
            .show()
    }

    private fun openNotificationSettingsScreen() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, notificationChannelId)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            try {
                startActivity(fallback)
            } catch (_: Exception) {
                Toast.makeText(this, "افتح إعدادات الهاتف ثم التطبيقات ثم FRESHLY ثم الإشعارات", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun createFreshlyNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "freshly_orders",
                "إشعارات FRESHLY",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "تنبيهات الطلبات الجديدة والتحديثات المهمة من تطبيق FRESHLY"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 150, 250)
                enableLights(true)
                lightColor = android.graphics.Color.rgb(212, 175, 55)
                setShowBadge(true)
            }
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun openHome() {
        webView.loadUrl("https://appassets.androidplatform.net/assets/home.html")
        requestNotificationPermissionIfNeeded()
    }

    private fun showPasswordSetup(onDone: () -> Unit) {
        val first = EditText(this).apply {
            hint = "كلمة مرور جديدة (6 أحرف على الأقل)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val second = EditText(this).apply {
            hint = "تأكيد كلمة المرور"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 0)
            addView(first); addView(second)
        }
        val dialog = AlertDialog.Builder(this).setTitle("تأمين تطبيق FRESHLY")
            .setMessage("أنشئ كلمة مرور خاصة بالتطبيق. يمكنك استخدام بصمة الهاتف بعد ذلك للدخول.")
            .setView(box).setCancelable(false).setPositiveButton("حفظ", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val a = first.text.toString()
                val b = second.text.toString()
                if (a.length < 6) { first.error = "6 أحرف على الأقل"; return@setOnClickListener }
                if (a != b) { second.error = "كلمتا المرور غير متطابقتين"; return@setOnClickListener }
                savePassword(a); dialog.dismiss(); onDone()
            }
        }
        dialog.show()
    }

    private fun savePassword(password: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString("password_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("password_hash", hashPassword(password, salt))
            .putBoolean("password_enabled", true).apply()
    }

    private fun hashPassword(password: String, salt: ByteArray): String {
        val spec = PBEKeySpec(password.toCharArray(), salt, 150000, 256)
        return try {
            Base64.encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded, Base64.NO_WRAP)
        } finally { spec.clearPassword() }
    }

    private fun verifyPassword(password: String): Boolean = try {
        val salt = Base64.decode(prefs.getString("password_salt", "") ?: "", Base64.NO_WRAP)
        val expected = prefs.getString("password_hash", "") ?: ""
        val actual = hashPassword(password, salt)
        java.security.MessageDigest.isEqual(actual.toByteArray(), expected.toByteArray())
    } catch (_: Exception) { false }

    private fun authenticateForEntry(onSuccess: () -> Unit) {
        val manager = BiometricManager.from(this)
        if (manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS) {
            val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onSuccess() }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        showPasswordUnlock(onSuccess)
                    }
                    override fun onAuthenticationFailed() {
                        Toast.makeText(this@MainActivity, "لم يتم التعرف على البصمة، حاول مرة أخرى", Toast.LENGTH_SHORT).show()
                    }
                })
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("الدخول إلى FRESHLY")
                .setSubtitle("استخدم بصمة الهاتف أو كلمة مرور التطبيق")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("استخدام كلمة المرور").build()
            prompt.authenticate(info)
        } else showPasswordUnlock(onSuccess)
    }

    private fun showPasswordUnlock(onSuccess: () -> Unit) {
        val input = EditText(this).apply {
            hint = "كلمة مرور التطبيق"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val dialog = AlertDialog.Builder(this).setTitle("تأكيد الدخول").setView(input)
            .setCancelable(false).setPositiveButton("دخول", null)
            .setNegativeButton("إلغاء") { _, _ -> if (!hasOpenedHome) finish() }.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (verifyPassword(input.text.toString())) { dialog.dismiss(); onSuccess() }
                else input.error = "كلمة المرور غير صحيحة"
            }
        }
        dialog.show()
    }

    @Deprecated("Deprecated for WebView file chooser compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == chooserRequest) {
            val result = if (resultCode == RESULT_OK && data?.data != null) arrayOf(data.data!!) else null
            fileChooserCallback?.onReceiveValue(result)
            fileChooserCallback = null
        }
    }

    override fun onDestroy() {
        printMonitor?.let { mainHandler.removeCallbacks(it) }
        printMonitor = null
        activePrintJob = null
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}