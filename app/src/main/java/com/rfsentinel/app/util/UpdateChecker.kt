package com.rfsentinel.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rfsentinel.app.BuildConfig
import com.rfsentinel.app.RFSentinelApp
import com.rfsentinel.app.kokoho.KokohoHubClient
import com.rfsentinel.app.kokoho.KokohoSignatures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Live update checker & installer backed by Kokoho Hub (`https://kokoho.tucsoncommunity.online/v1/update/latest`
 * and `/v1/update/kokoho-latest.apk`), with no dependency on GitHub.
 *
 * - Silently checks `kokoho-hub` on startup when Auxnet is enabled.
 * - Never prompts the user to enable GitHub checks.
 * - When a newer release is available on `kokoho-hub`, shows a dismissable popup AND surfaces
 *   an "Update App" card in the Utility tab until installed.
 */
object UpdateChecker {

    /**
     * Release metadata returned by `GET /v1/update/latest` on `kokoho-hub`
     * (also compatible with GitHub release JSON format for unit tests / future dual hosting).
     */
    data class Latest(
        val version: String,
        val apkUrl: String?,
        val notesUrl: String,
        val apkSize: Long = 0L,
        val apkSha256: String? = null,
        val versionCode: Int = 0,
        val notes: String = ""
    )

    @Volatile
    var availableUpdate: Latest? = null
        private set

    @Volatile
    private var promptedVersion: String? = null

    private val listeners = CopyOnWriteArrayList<(Latest?) -> Unit>()

    fun addListener(listener: (Latest?) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(availableUpdate)
        return { listeners.remove(listener) }
    }

    private fun notifyListeners(latest: Latest?) {
        availableUpdate = latest
        for (l in listeners) {
            runCatching { l(latest) }
        }
    }

    /** True when [a] is a newer dotted version than [b] ("2.42.0" > "2.41.0"). */
    fun isNewer(a: String, b: String): Boolean {
        val x = a.trimStart('v', 'V').substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.trimStart('v', 'V').substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    fun isUpdateAvailable(latest: Latest): Boolean {
        val currentVer = BuildConfig.VERSION_NAME.substringBefore('-')
        val remoteVer = latest.version.trimStart('v', 'V').substringBefore('-')
        if (remoteVer == currentVer) return false
        return isNewer(remoteVer, currentVer)
    }

    internal fun parseLatest(text: String, foss: Boolean = BuildConfig.FLAVOR == "foss"): Latest =
        parse(JSONObject(text), foss)

    internal fun parseNewest(text: String, foss: Boolean = BuildConfig.FLAVOR == "foss"): Latest {
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) {
            return parse(JSONObject(trimmed), foss)
        }
        val list = JSONArray(trimmed)
        return (0 until list.length())
            .map { list.getJSONObject(it) }
            .filter { !it.optBoolean("draft") }
            .map { parse(it, foss) }
            .reduceOrNull { a, b -> if (isNewer(b.version, a.version)) b else a }
            ?: throw IllegalStateException("No releases found")
    }

    private fun parse(json: JSONObject, foss: Boolean): Latest {
        // 1. Native kokoho-hub /v1/update/latest format
        if (json.has("version") || json.has("apk_url")) {
            val ver = json.optString("version", BuildConfig.VERSION_NAME).trimStart('v', 'V')
            val vCode = json.optInt("version_code", 0)
            val apkUrl = json.optString("apk_url", "").takeIf { it.isNotBlank() }
            val size = json.optLong("apk_size", 0L)
            val sha = json.optString("apk_sha256", "").takeIf { it.isNotBlank() }?.removePrefix("sha256:")
            val notesUrl = json.optString("notes_url", apkUrl ?: KokohoHubClient.DEFAULT_HUB_URL)
            val notes = json.optString("notes", "")
            return Latest(ver, apkUrl, notesUrl, size, sha, vCode, notes)
        }

        // 2. Fallback compatibility with GitHub release JSON format
        val suffix = if (foss) "-foss.apk" else "-release.apk"
        val assets = json.optJSONArray("assets")
        var apk: String? = null
        var size = 0L
        var sha: String? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                if (name.endsWith(suffix)) {
                    apk = a.optString("browser_download_url")
                    size = a.optLong("size", 0L)
                    sha = a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
                    break
                }
            }
        }
        return Latest(
            version = json.getString("tag_name").trimStart('v', 'V'),
            apkUrl = apk,
            notesUrl = json.optString("html_url", KokohoHubClient.DEFAULT_HUB_URL),
            apkSize = size,
            apkSha256 = sha
        )
    }

    private fun fetchFromHub(context: Context): Latest {
        val base = KokohoHubClient.hubUrl(context)
        val url = "$base/v1/update/latest"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Kokoho/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (conn.responseCode != 200) throw IllegalStateException("Update server answered HTTP ${conn.responseCode}")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val parsed = parseLatest(text)
            val resolvedApk = parsed.apkUrl?.let { raw ->
                if (raw.startsWith("http://") || raw.startsWith("https://")) raw
                else "$base/${raw.trimStart('/')}"
            }
            return parsed.copy(apkUrl = resolvedApk)
        } finally {
            conn.disconnect()
        }
    }

    private const val AUTO_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val RETRY_MS = 30 * 60 * 1000L
    @Volatile
    private var checking = false

    internal fun due(last: Long, now: Long) = now - last >= AUTO_INTERVAL_MS || now < last

    /**
     * Silent startup check against `kokoho.tucsoncommunity.online/v1/update/latest`.
     * Never prompts about GitHub. If a newer version is hosted on `kokoho-hub`, surfaces
     * a dismissable dialog and adds the "Update App" card to the Utility tab.
     */
    fun checkOnStartup(activity: AppCompatActivity) {
        if (!KokohoSignatures.isAuxnetEnabled(activity) || checking) return
        if (!due(Prefs.lastUpdateCheck(activity), System.currentTimeMillis())) return
        check(activity, silent = true)
    }

    fun check(activity: AppCompatActivity, silent: Boolean = false) {
        if (!silent) Toast.makeText(activity, "Checking Kokoho Hub for updates…", Toast.LENGTH_SHORT).show()
        if (checking) return
        checking = true
        val appCtx = activity.applicationContext
        activity.lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { runCatching { fetchFromHub(appCtx) } }
            } finally {
                checking = false
            }
            val now = System.currentTimeMillis()
            Prefs.setLastUpdateCheck(activity, if (result.isSuccess) now else now - AUTO_INTERVAL_MS + RETRY_MS)
            if (activity.isFinishing || activity.isDestroyed) return@launch
            if (silent && result.isFailure) return@launch

            val latest = result.getOrElse {
                MaterialAlertDialogBuilder(activity)
                    .setTitle("Couldn't check for updates")
                    .setMessage("Kokoho Hub (${KokohoHubClient.hubUrl(activity)}) could not be reached (${it.message ?: "no connection"}). Check your connection or Auxnet Hub settings.")
                    .setPositiveButton("Close", null)
                    .show()
                return@launch
            }

            if (isUpdateAvailable(latest)) {
                notifyListeners(latest)
                if (!silent || promptedVersion != latest.version) {
                    promptedVersion = latest.version
                    showUpdateAvailableDialog(activity, latest)
                }
            } else {
                notifyListeners(null)
                if (!silent) {
                    val current = BuildConfig.VERSION_NAME.substringBefore('-')
                    MaterialAlertDialogBuilder(activity)
                        .setTitle("You're up to date")
                        .setMessage("Kokoho $current is the latest version on ${KokohoHubClient.hubUrl(activity)}.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    fun showUpdateAvailableDialog(activity: AppCompatActivity, latest: Latest) {
        val notesLine = latest.notes.takeIf { it.isNotBlank() }?.let { "\n\n$it" }.orEmpty()
        MaterialAlertDialogBuilder(activity)
            .setTitle("Update available (${latest.version})")
            .setMessage(
                "A newer version of Kokoho is available. You can update now or anytime from Utility.$notesLine"
            )
            .setCancelable(true)
            .setPositiveButton("Update now") { _, _ -> installNow(activity, latest) }
            .setNegativeButton("Later", null)
            .show()
    }

    fun installNow(activity: AppCompatActivity, latest: Latest) {
        val url = latest.apkUrl ?: return open(activity, latest.notesUrl)
        if (!UpdateInstaller.canInstall(activity)) {
            askToAllowInstalls(activity, latest)
            return
        }

        val d = activity.resources.displayMetrics.density
        val text = android.widget.TextView(activity).apply { text = "Starting live update download…" }
        val bar = com.google.android.material.progressindicator.LinearProgressIndicator(activity).apply {
            isIndeterminate = true
        }
        val box = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (16 * d).toInt(), (24 * d).toInt(), (8 * d).toInt())
            addView(text)
            addView(bar, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * d).toInt() })
        }
        var job: kotlinx.coroutines.Job? = null
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Updating to Kokoho ${latest.version}")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> job?.cancel() }
            .show()

        fun alive() = !activity.isFinishing && !activity.isDestroyed
        fun mb(n: Long) = "%.1f MB".format(Locale.US, n / 1_048_576.0)

        job = (activity.application as RFSentinelApp).appScope.launch(Dispatchers.Main) {
            try {
                val file = UpdateInstaller.download(
                    activity.applicationContext,
                    url,
                    latest.version,
                    latest.apkSize,
                    latest.apkSha256
                ) { done, total ->
                    if (alive()) activity.runOnUiThread {
                        if (total > 0) {
                            bar.isIndeterminate = false
                            bar.max = 1000
                            bar.progress = (done * 1000 / total).toInt()
                            text.text = "Downloading ${mb(done)} of ${mb(total)}"
                        } else {
                            text.text = "Downloading ${mb(done)}"
                        }
                    }
                }
                if (alive()) {
                    bar.isIndeterminate = true
                    text.text = "Installing… confirm Android's \"Update\" prompt"
                }
                val outcome = UpdateInstaller.install(activity.applicationContext, file)
                if (alive()) dialog.dismiss()
                when (outcome) {
                    is UpdateInstaller.Outcome.Cancelled -> if (alive()) Toast.makeText(
                        activity,
                        "Update cancelled — you can install it from Utility anytime",
                        Toast.LENGTH_LONG
                    ).show()
                    is UpdateInstaller.Outcome.Failed -> if (alive()) failed(activity, latest, outcome.message)
                    is UpdateInstaller.Outcome.Refused -> if (alive()) failed(activity, latest, "Android refused the update")
                    else -> {}
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                if (alive()) {
                    dialog.dismiss()
                    Toast.makeText(activity, "Update cancelled", Toast.LENGTH_SHORT).show()
                }
                UpdateInstaller.cleanUp(activity.applicationContext)
            } catch (e: Exception) {
                if (alive()) {
                    dialog.dismiss()
                    failed(activity, latest, e.message ?: "no connection")
                }
            }
        }
    }

    private fun failed(activity: AppCompatActivity, latest: Latest, why: String) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Couldn't install the update")
            .setMessage("$why\n\nYou can also download Kokoho ${latest.version} directly from ${latest.apkUrl ?: latest.notesUrl}.")
            .setPositiveButton("Download in browser") { _, _ -> open(activity, latest.apkUrl ?: latest.notesUrl) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun askToAllowInstalls(activity: AppCompatActivity, latest: Latest) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Allow Kokoho to install updates")
            .setMessage(
                "Android requires allowing \"Install unknown apps\" once for Kokoho so live updates from Kokoho Hub can install directly."
            )
            .setPositiveButton("Open setting") { _, _ ->
                var left = false
                activity.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
                    override fun onStop(owner: androidx.lifecycle.LifecycleOwner) { left = true }
                    override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                        if (!left) return
                        activity.lifecycle.removeObserver(this)
                        if (UpdateInstaller.canInstall(activity)) installNow(activity, latest)
                        else Toast.makeText(activity, "Permission not granted — update not started", Toast.LENGTH_LONG).show()
                    }
                })
                runCatching { activity.startActivity(UpdateInstaller.allowInstallsIntent(activity)) }
            }
            .setNeutralButton("Download in browser") { _, _ -> open(activity, latest.apkUrl ?: latest.notesUrl) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun open(activity: AppCompatActivity, url: String) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}
