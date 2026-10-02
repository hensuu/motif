package app.hensuu.motif

import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Minimal Google Drive v3 client over HttpURLConnection.
 *
 * Uses the `drive.file` scope: the app can only see files and folders it created itself, never the
 * rest of your Drive. Auth is Google Identity Services' AuthorizationClient, so there is no client
 * secret in the app — Google matches the package name + signing SHA-1 to the OAuth client.
 */
object Drive {

    const val FOLDER_NAME = "Motif"
    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val API = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    private const val AUDIO_MIME = "audio/mp4"

    /** Access lost or never granted; the user has to reconnect from the app. */
    class AuthRequired : Exception()

    /** The server rejected the request in a way retrying won't fix. */
    class Rejected(code: Int, body: String) : Exception("HTTP $code: $body")

    fun authRequest(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /** Blocking; call off the main thread. Returns a token without UI, or throws [AuthRequired]. */
    fun token(c: Context): String {
        val result = Tasks.await(
            Identity.getAuthorizationClient(c).authorize(authRequest()), 30, TimeUnit.SECONDS,
        )
        if (result.hasResolution()) throw AuthRequired()
        return result.accessToken ?: throw AuthRequired()
    }

    /** Uploads [file] into the Motif folder, refreshing the token once if it has expired. */
    fun upload(c: Context, file: File) {
        var token = token(c)
        try {
            uploadWith(c, token, file)
        } catch (e: AuthRequired) {
            try {
                GoogleAuthUtil.clearToken(c, token)
            } catch (ignored: Exception) {
            }
            token = token(c)
            uploadWith(c, token, file)
        }
    }

    private fun uploadWith(c: Context, token: String, file: File) {
        val folder = ensureFolder(c, token)

        // Resumable session: works for any size (simple uploads cap at 5 MB).
        val meta = JSONObject()
            .put("name", file.name)
            .put("mimeType", AUDIO_MIME)
            .put("parents", JSONArray().put(folder))
            .toString()
        val init = open("$UPLOAD?uploadType=resumable&fields=id", "POST", token)
        init.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        init.setRequestProperty("X-Upload-Content-Type", AUDIO_MIME)
        init.setRequestProperty("X-Upload-Content-Length", file.length().toString())
        writeBody(init, meta)
        read(init)
        val session = init.getHeaderField("Location") ?: throw IOException("No upload session")
        init.disconnect()

        val put = open(session, "PUT", token)
        put.setRequestProperty("Content-Type", AUDIO_MIME)
        put.doOutput = true
        put.setFixedLengthStreamingMode(file.length())
        put.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
        read(put)
        put.disconnect()
    }

    /** The app's "Motif" folder: cached id → look it up → create it. */
    @Synchronized
    private fun ensureFolder(c: Context, token: String): String {
        Prefs.folderId(c)?.let { id ->
            val conn = open("$API/$id?fields=id,trashed", "GET", token)
            if (conn.responseCode == 404) {
                conn.disconnect()
            } else {
                val json = JSONObject(read(conn))
                if (!json.optBoolean("trashed")) return id
            }
            Prefs.setFolderId(c, null)
        }

        val q = "name = '$FOLDER_NAME' and mimeType = '$FOLDER_MIME' and trashed = false"
        val search = open(
            "$API?q=${URLEncoder.encode(q, "UTF-8")}&spaces=drive&fields=files(id)", "GET", token,
        )
        val files = JSONObject(read(search)).optJSONArray("files")
        if (files != null && files.length() > 0) {
            return files.getJSONObject(0).getString("id").also { Prefs.setFolderId(c, it) }
        }

        val create = open("$API?fields=id", "POST", token)
        create.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        writeBody(create, JSONObject().put("name", FOLDER_NAME).put("mimeType", FOLDER_MIME).toString())
        return JSONObject(read(create)).getString("id").also { Prefs.setFolderId(c, it) }
    }

    private fun open(url: String, method: String, token: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
        }

    private fun writeBody(conn: HttpURLConnection, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.outputStream.use { it.write(bytes) }
    }

    /** Returns the body on 2xx; maps failures to retry (IOException) / reconnect / give up. */
    private fun read(conn: HttpURLConnection): String {
        val code = conn.responseCode
        if (code in 200..299) return conn.inputStream.bufferedReader().use { it.readText() }
        val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        when {
            code == 401 -> throw AuthRequired()
            code == 403 && "rateLimit" !in body && "userRateLimit" !in body -> throw Rejected(code, body)
            code == 408 || code == 429 || code >= 500 || code == 403 -> throw IOException("HTTP $code")
            else -> throw Rejected(code, body)
        }
    }
}
