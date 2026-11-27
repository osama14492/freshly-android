package com.freshly.premier

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.app.NotificationCompat

class OrderSoundBridge(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "freshly_new_orders_channel"
        const val JS_INTERFACE_NAME = "FreshlyNative"
    }

    fun attachTo(webView: WebView) {
        webView.settings.javaScriptEnabled = true
        webView.addJavascriptInterface(this, JS_INTERFACE_NAME)
    }

    @JavascriptInterface
    fun showLocalOrderAlert(orderId: String, title: String, body: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val channel = NotificationChannel(
                CHANNEL_ID,
                "تنبيهات الطلبات الجديدة",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "إشعار صوتي فوري عند وصول طلب جديد"
                enableVibration(true)
                setSound(soundUri, attrs)
            }
            manager.createNotificationChannel(channel)
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            } ?: Intent()

        val requestCode = if (orderId.isBlank()) {
            (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        } else {
            orderId.hashCode()
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(title.ifBlank { "طلب جديد في FRESHLY" })
            .setContentText(body.ifBlank { "تم استلام طلب جديد رقم $orderId" })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setSound(soundUri)
            .setVibrate(longArrayOf(0, 300, 150, 300))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(requestCode, notification)
    }
}
