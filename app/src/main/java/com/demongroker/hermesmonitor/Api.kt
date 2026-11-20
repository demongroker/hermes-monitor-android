package com.demongroker.hermesmonitor

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal Mission Control API client.
 * Auth: POST /api/login {"token": ...} returns an HttpOnly session cookie;
 * a Bearer <token> header is also accepted on every /api/* call.
 */
object Api {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    data class LoginResult(val ok: Boolean, val message: String, val cookies: List<String>)

    /** POST /api/login. Returns success plus any Set-Cookie headers for WebView injection. */
    fun login(baseUrl: String, token: String): LoginResult {
        val body = JSONObject().put("token", token).toString().toRequestBody(jsonType)
        val request = Request.Builder()
            .url("$baseUrl/api/login")
            .post(body)
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                val cookies = resp.headers("Set-Cookie")
                if (resp.isSuccessful) {
                    LoginResult(true, "Connected", cookies)
                } else {
                    val detail = when (resp.code) {
                        401, 403 -> "Token rejected (HTTP ${resp.code}). Check the dashboard token."
                        else -> "Server returned HTTP ${resp.code}."
                    }
                    LoginResult(false, detail, emptyList())
                }
            }
        } catch (e: Exception) {
            LoginResult(false, "Could not reach server: ${e.javaClass.simpleName}" +
                (e.message?.let { " — $it" } ?: ""), emptyList())
        }
    }

    /** GET /api/state with Bearer auth. Throws on network/HTTP failure. */
    fun fetchState(baseUrl: String, token: String): JSONObject {
        val request = Request.Builder()
            .url("$baseUrl/api/state")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code} from /api/state")
            }
            val text = resp.body?.string() ?: throw IllegalStateException("Empty /api/state body")
            return JSONObject(text)
        }
    }
}
