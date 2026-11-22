package com.arnod.arno_link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

@Suppress("DEPRECATION")
class MainActivity : FlutterActivity() {
    private var nid = 100
    private var wifiCb: ConnectivityManager.NetworkCallback? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, "arno/notifs").setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(args: Any?, events: EventChannel.EventSink?) { NotifService.sink = events }
            override fun onCancel(args: Any?) { NotifService.sink = null }
        })
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "arno/native").setMethodCallHandler { call, result ->
            when (call.method) {
                "media" -> {
                    val code = when (call.argument<String>("key")) {
                        "pp" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                        "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                        else -> 0
                    }
                    if (code != 0) {
                        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                    }
                    result.success(true)
                }
                "notify" -> {
                    val title = call.argument<String>("title") ?: "Desk Buddy"
                    val body = call.argument<String>("body") ?: ""
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    val b: Notification.Builder
                    if (Build.VERSION.SDK_INT >= 26) {
                        val ch = NotificationChannel("arno_buddy", "Desk Buddy", NotificationManager.IMPORTANCE_HIGH)
                        nm.createNotificationChannel(ch)
                        b = Notification.Builder(this, "arno_buddy")
                    } else {
                        b = Notification.Builder(this)
                    }
                    b.setSmallIcon(applicationInfo.icon).setContentTitle(title).setContentText(body)
                        .setStyle(Notification.BigTextStyle().bigText(body)).setAutoCancel(true)
                    nm.notify(nid++, b.build())
                    result.success(true)
                }
                "listenerEnabled" -> {
                    val en = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
                    result.success(en.contains(packageName))
                }
                "openListener" -> {
                    val i = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                    result.success(true)
                }
                "openUrl" -> {
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse(call.argument<String>("url") ?: "http://192.168.4.1"))
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                    result.success(true)
                }
                "bindWifi" -> {
                    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val on = call.argument<Boolean>("on") ?: false
                    try {
                        wifiCb?.let { cm.unregisterNetworkCallback(it) }
                    } catch (e: Exception) { }
                    wifiCb = null
                    if (on) {
                        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
                        val cb = object : ConnectivityManager.NetworkCallback() {
                            override fun onAvailable(network: Network) { cm.bindProcessToNetwork(network) }
                        }
                        wifiCb = cb
                        try { cm.requestNetwork(req, cb) } catch (e: Exception) { }
                    } else {
                        try { cm.bindProcessToNetwork(null) } catch (e: Exception) { }
                    }
                    result.success(true)
                }
                "joinWifi" -> {
                    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    try { wifiCb?.let { cm.unregisterNetworkCallback(it) } } catch (e: Exception) { }
                    wifiCb = null
                    if (Build.VERSION.SDK_INT < 29) { result.success(false) } else {
                        val spec = WifiNetworkSpecifier.Builder().setSsid(call.argument<String>("ssid") ?: "").setWpa2Passphrase(call.argument<String>("pass") ?: "").build()
                        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(spec).build()
                        var replied = false
                        val cb = object : ConnectivityManager.NetworkCallback() {
                            override fun onAvailable(network: Network) {
                                cm.bindProcessToNetwork(network)
                                if (!replied) { replied = true; runOnUiThread { result.success(true) } }
                            }
                            override fun onUnavailable() { if (!replied) { replied = true; runOnUiThread { result.success(false) } } }
                        }
                        wifiCb = cb
                        try { cm.requestNetwork(req, cb, 30000) } catch (e: Exception) { if (!replied) { replied = true; result.success(false) } }
                    }
                }
                else -> result.notImplemented()
            }
        }
    }
}
