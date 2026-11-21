package com.opengate.auto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * שער. target קובע את שיטת הפתיחה:
 *   מתחיל ב-"api:"  -> פתיחה דרך OpenGate (callNow). מה שאחרי "api:" הוא gateId.
 *   מתחיל ב-"http"  -> GET ישיר לכתובת.
 *   אחרת            -> מספר טלפון לחיוג.
 */
data class Gate(val name: String, val target: String) {
    val isApi: Boolean get() = target.startsWith("api:", true)
    val isHttp: Boolean get() = target.startsWith("http://", true) || target.startsWith("https://", true)
    val gateId: String get() = target.substring(4)
}

object GateStore {
    private const val PREFS = "gates"
    private const val KEY_LIST = "list"
    private const val KEY_UQID = "token"   // uqid - מזהה ההתחברות של OpenGate
    private const val KEY_BASE = "baseUrl"
    const val DEFAULT_BASE = "https://api.opengate.io"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun baseUrl(ctx: Context): String = prefs(ctx).getString(KEY_BASE, DEFAULT_BASE) ?: DEFAULT_BASE
    fun setBaseUrl(ctx: Context, v: String) = prefs(ctx).edit().putString(KEY_BASE, v.trim().ifEmpty { DEFAULT_BASE }).apply()
    fun uqid(ctx: Context): String = prefs(ctx).getString(KEY_UQID, "") ?: ""
    fun setUqid(ctx: Context, v: String) = prefs(ctx).edit().putString(KEY_UQID, v.trim()).apply()

    fun load(ctx: Context): List<Gate> {
        val raw = prefs(ctx).getString(KEY_LIST, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i -> val o = arr.getJSONObject(i); Gate(o.getString("name"), o.getString("target")) }
        } catch (e: Exception) { emptyList() }
    }

    fun save(ctx: Context, gates: List<Gate>) {
        val arr = JSONArray()
        gates.forEach { arr.put(JSONObject().put("name", it.name).put("target", it.target)) }
        prefs(ctx).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    fun open(ctx: Context, gate: Gate, onResult: (String) -> Unit) {
        when {
            gate.isApi  -> openApi(ctx, gate, onResult)
            gate.isHttp -> openRawHttp(gate.target, gate.name, onResult)
            else        -> openCall(ctx, gate, onResult)
        }
    }

    private fun openApi(ctx: Context, gate: Gate, onResult: (String) -> Unit) {
        val id = uqid(ctx)
        if (id.isEmpty()) { onResult("לא מחובר - פתח הגדרות והתחבר"); return }
        // כמו האפליקציה המקורית: GET עם uqid כפרמטר, ואין כותרת אימות.
        val url = baseUrl(ctx).trimEnd('/') +
            "/gates/callNow?uqid=${Uri.encode(id)}&gateId=${Uri.encode(gate.gateId)}" +
            "&type=manualCall&userLocation="
        enqueue(Request.Builder().url(url).get().header("Accept", "application/json").build(), gate.name, onResult)
    }

    private fun openRawHttp(url: String, name: String, onResult: (String) -> Unit) {
        val req = try { Request.Builder().url(url).build() }
            catch (e: IllegalArgumentException) { onResult("כתובת לא תקינה"); return }
        enqueue(req, name, onResult)
    }

    private fun enqueue(req: Request, name: String, onResult: (String) -> Unit) {
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { main.post { onResult("$name: אין תגובה") } }
            override fun onResponse(call: Call, response: Response) {
                val ok = response.isSuccessful; val code = response.code; response.close()
                main.post { onResult(if (ok) "$name: נפתח" else "$name: שגיאה $code") }
            }
        })
    }

    private fun openCall(ctx: Context, gate: Gate, onResult: (String) -> Unit) {
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) { onResult("חסרה הרשאת חיוג - פתח את האפליקציה בטלפון ואשר"); return }
        try {
            val telecom = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.placeCall(Uri.fromParts("tel", gate.target, null), Bundle())
            onResult("${gate.name}: מחייג")
        } catch (e: Exception) { onResult("החיוג נכשל") }
    }

    // ---- התחברות SMS (בדיוק כמו האפליקציה המקורית) ----

    /** POST /sms/generateSmsCode  body {ext, phone} */
    fun sendSmsCode(ctx: Context, ext: String, phone: String, onResult: (Boolean, String) -> Unit) {
        val body = JSONObject().put("ext", ext).put("phone", phone).toString().toRequestBody(JSON)
        val req = Request.Builder().url(baseUrl(ctx).trimEnd('/') + "/sms/generateSmsCode").post(body).build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { main.post { onResult(false, "אין תגובה מהשרת") } }
            override fun onResponse(call: Call, response: Response) {
                val ok = response.isSuccessful; val sc = response.code; val txt = response.body?.string() ?: ""; response.close()
                main.post { onResult(ok, if (ok) "נשלח קוד ב-SMS" else "שגיאה $sc: ${txt.take(160)}") }
            }
        })
    }

    /** POST /sms/verifySmsCode  body {ext, phone, smsCode, platform, deviceVersion, deviceType} -> uqid */
    fun verifySmsCode(ctx: Context, ext: String, phone: String, smsCode: String, onResult: (Boolean, String) -> Unit) {
        val body = JSONObject()
            .put("ext", ext).put("phone", phone).put("smsCode", smsCode)
            .put("platform", "android").put("deviceType", "android")
            .put("deviceVersion", Build.VERSION.RELEASE ?: "")
            .toString().toRequestBody(JSON)
        val req = Request.Builder().url(baseUrl(ctx).trimEnd('/') + "/sms/verifySmsCode").post(body).build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { main.post { onResult(false, "אין תגובה מהשרת") } }
            override fun onResponse(call: Call, response: Response) {
                val txt = response.body?.string() ?: ""; val okHttp = response.isSuccessful; val sc = response.code; response.close()
                val id = extractUqid(txt)
                main.post {
                    if (id != null) { setUqid(ctx, id); onResult(true, "התחברת בהצלחה") }
                    else onResult(false, if (okHttp) "לא נמצא מזהה בתשובה: ${txt.take(200)}" else "שגיאה $sc: ${txt.take(200)}")
                }
            }
        })
    }

    private fun extractUqid(txt: String): String? {
        return try {
            val o = JSONObject(txt)
            uqidFrom(o) ?: uqidFrom(o.optJSONObject("data")) ?: uqidFrom(o.optJSONObject("user"))
                ?: uqidFrom(o.optJSONObject("userInfo"))
        } catch (e: Exception) { null }
    }
    private fun uqidFrom(o: JSONObject?): String? {
        if (o == null) return null
        for (k in listOf("uqid", "token", "accessToken")) { val v = o.optString(k, ""); if (v.isNotEmpty() && v != "null") return v }
        return null
    }

    // ---- טעינת רשימת השערים ----

    /** GET /gates?uqid=... */
    fun fetchGates(ctx: Context, onResult: (Boolean, List<Gate>?, String) -> Unit) {
        val id = uqid(ctx)
        if (id.isEmpty()) { onResult(false, null, "לא מחובר - התחבר קודם"); return }
        val url = baseUrl(ctx).trimEnd('/') + "/gates?uqid=${Uri.encode(id)}"
        val req = Request.Builder().url(url).get().header("Accept", "application/json").build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { main.post { onResult(false, null, "אין תגובה מהשרת") } }
            override fun onResponse(call: Call, response: Response) {
                val txt = response.body?.string() ?: ""; val okHttp = response.isSuccessful; val sc = response.code; response.close()
                val gates = parseGates(txt)
                main.post {
                    when {
                        gates != null && gates.isNotEmpty() -> onResult(true, gates, "נטענו ${gates.size} שערים")
                        gates != null -> onResult(false, null, "לא נמצאו שערים בחשבון")
                        okHttp -> onResult(false, null, "מבנה תשובה לא מזוהה: ${txt.take(200)}")
                        else -> onResult(false, null, "שגיאה $sc: ${txt.take(200)}")
                    }
                }
            }
        })
    }

    private fun parseGates(txt: String): List<Gate>? {
        val arr: JSONArray = try { JSONArray(txt) }
        catch (e: Exception) {
            try { val o = JSONObject(txt); findArray(o) ?: findArray(o.optJSONObject("data")) ?: return null }
            catch (e2: Exception) { return null }
        }
        val out = ArrayList<Gate>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = firstNonEmpty(o, "gateId", "_id", "id", "gateID") ?: continue
            val name = firstNonEmpty(o, "nickname", "gateName", "name", "title") ?: id
            out.add(Gate(name, "api:$id"))
        }
        return out
    }
    private fun findArray(o: JSONObject?): JSONArray? {
        if (o == null) return null
        for (k in listOf("gates", "data", "result", "items", "list")) { val a = o.optJSONArray(k); if (a != null) return a }
        return null
    }
    private fun firstNonEmpty(o: JSONObject, vararg keys: String): String? {
        for (k in keys) { val v = o.optString(k, ""); if (v.isNotEmpty() && v != "null") return v }
        return null
    }
}
