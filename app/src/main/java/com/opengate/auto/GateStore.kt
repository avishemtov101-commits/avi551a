package com.opengate.auto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** שער: target שמתחיל ב-http נפתח בקריאת רשת, כל דבר אחר הוא מספר טלפון לחיוג. */
data class Gate(val name: String, val target: String) {
    val isHttp: Boolean get() = target.startsWith("http://", true) || target.startsWith("https://", true)
}

object GateStore {
    private const val PREFS = "gates"
    private const val KEY = "list"

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun load(ctx: Context): List<Gate> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Gate(o.getString("name"), o.getString("target"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(ctx: Context, gates: List<Gate>) {
        val arr = JSONArray()
        gates.forEach { arr.put(JSONObject().put("name", it.name).put("target", it.target)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    /** פותח את השער. onResult נקרא תמיד על ה-main thread עם הודעה קצרה למשתמש. */
    fun open(ctx: Context, gate: Gate, onResult: (String) -> Unit) {
        if (gate.isHttp) openHttp(gate, onResult) else openCall(ctx, gate, onResult)
    }

    private fun openHttp(gate: Gate, onResult: (String) -> Unit) {
        val request = try {
            Request.Builder().url(gate.target).build()
        } catch (e: IllegalArgumentException) {
            onResult("כתובת לא תקינה")
            return
        }
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.post { onResult("${gate.name}: אין תגובה") }
            }

            override fun onResponse(call: Call, response: Response) {
                val ok = response.isSuccessful
                val code = response.code
                response.close()
                main.post { onResult(if (ok) "${gate.name}: נפתח" else "${gate.name}: שגיאה $code") }
            }
        })
    }

    private fun openCall(ctx: Context, gate: Gate, onResult: (String) -> Unit) {
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            onResult("חסרה הרשאת חיוג - פתח את האפליקציה בטלפון ואשר")
            return
        }
        try {
            // placeCall אינו פתיחת Activity, ולכן עובד גם מתוך שירות הרכב ברקע.
            val telecom = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.placeCall(Uri.fromParts("tel", gate.target, null), Bundle())
            onResult("${gate.name}: מחייג")
        } catch (e: Exception) {
            onResult("החיוג נכשל")
        }
    }
}
