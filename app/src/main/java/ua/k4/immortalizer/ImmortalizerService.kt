package ua.k4.immortalizer

import android.app.Service
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONArray
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.Path
import kotlin.io.path.isExecutable
import kotlin.io.path.notExists
import kotlin.io.path.writeText

class ImmortalizerService : Service() {
    private lateinit var favorites: Uri
    private lateinit var favoritesString: String
    private lateinit var parseStrategy: (String) -> List<String>
    private lateinit var pipeFile: Path

    private val strategies = hashMapOf(
        "content://settings/system/recent_panel_favorites" to ::parseStrategySlim,
        "content://settings/system/locked_apps" to ::parseStrategyJson,
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()

        pipeFile = Path(applicationContext.filesDir.path, "pipe")
        thread { setResult(checkAndRun()) }
    }

    private fun checkAndRun(): String? {
        val systemPath = System.getenv("PATH")?.split(":") ?: return getString(R.string.no_path)
        arrayOf("su", "sh", "mkfifo").filter { program ->
            !systemPath.any { directory ->
                Path(
                    directory, program
                ).isExecutable()
            }
        }.let {
            if (it.isNotEmpty()) return getString(R.string.no_programs) + it.joinToString(prefix = " ")
        }

        if (pipeFile.notExists()) ProcessBuilder("mkfifo", pipeFile.toString()).start().waitFor()
            .let {
                if (it != 0) return getString(R.string.mkfifo_failed)
            }

        val pipeCheck = ProcessBuilder("sh", "-c", "echo true > '$pipeFile'").start()
        if (!pipeCheck.waitFor(1, TimeUnit.SECONDS)) {
            pipeCheck.destroy()
            val su = ProcessBuilder(
                "su", "-c", "id -u; while true; do source '$pipeFile' || break; done"
            ).start()
            if (su.inputStream.bufferedReader().readLine() != "0") {
                su.destroy()
                return getString(R.string.no_root)
            }
        }

        // disable or enable battery optimization for this app
        val action = if (Settings(
                Path(
                    applicationContext.filesDir.path,
                    "settings.json"
                )
            ).disableBatteryOptimization
        ) "+" else "-"
        pipeFile.writeText("dumpsys deviceidle whitelist $action$packageName")

        val uri = strategies.keys.find {
            readFavorites(Uri.parse(it)) != null
        } ?: return getString(R.string.favorites_not_supported)
        favorites = Uri.parse(uri)
        parseStrategy = strategies[uri]!!
        thread {
            Looper.prepare()
            contentResolver.registerContentObserver(
                favorites, true, FavoritesObserver(
                    this, Handler(Looper.myLooper()!!)
                )
            )
            Looper.loop()
        }
        updateFavorites()

        isRunning = true
        thread { mainLoop() }
        return null
    }

    private fun mainLoop() {
        while (isRunning) {
            Thread.sleep(1000)
            pipeFile.writeText("for name in $favoritesString; do for pid in \$(pidof \$name); do echo '-1000' > /proc/\$pid/oom_score_adj; done; done")
        }
    }

    private fun readFavorites(uri: Uri = favorites): String? {
        val cursor = contentResolver.query(
            uri,
            arrayOf("value"),
            null,
            null,
            null,
        ) ?: return null
        if (!cursor.moveToFirst()) {
            cursor.close()
            return null
        }

        return cursor.getString(0)
    }

    fun updateFavorites() {
        val favorites = readFavorites() ?: return
        favoritesString = parseStrategy(favorites).plus(packageName).toHashSet().joinToString(" ")
    }

    private fun parseStrategySlim(data: String): List<String> {
        return data.split("|").map { it.substringAfter(":").substringBefore("/") }
    }

    private fun parseStrategyJson(data: String): List<String> {
        val arr = JSONArray(data)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.getInt("u") == 0) {
                val jsonList = obj.getJSONArray("pkgs")
                val list = ArrayList<String>()
                for (j in 0 until jsonList.length()) list.add(jsonList.getString(j))
                return list
            }
        }
        return listOf()
    }

    companion object {
        var isRunning = false
        private var result = CompletableFuture<String?>()

        fun getResult(): String? {
            val res = result.get()
            result = CompletableFuture()
            return res
        }

        private fun setResult(value: String?) {
            result.complete(value)
        }
    }
}

class FavoritesObserver(private val service: ImmortalizerService, h: Handler) : ContentObserver(h) {
    override fun deliverSelfNotifications(): Boolean {
        return false
    }

    override fun onChange(selfChange: Boolean) {
        super.onChange(selfChange)
        service.updateFavorites()
    }
}
