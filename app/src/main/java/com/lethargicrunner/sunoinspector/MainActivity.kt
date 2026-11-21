package com.lethargicrunner.sunoinspector

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Base64
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.ListView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Suno Inspector
 *
 * Ouvre suno.com dans une WebView, observe les réponses JSON de l'API du site
 * (fetch / XMLHttpRequest) pour repérer les morceaux (clips) de ta bibliothèque,
 * puis télécharge les MP3 (et les paroles) dans un dossier de ton choix.
 *
 * Rien n'est envoyé ailleurs : la bibliothèque détectée reste dans le stockage privé
 * de l'application, et les MP3 sont téléchargés depuis le CDN de Suno.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var statusView: TextView
    private lateinit var downloadButton: Button

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val downloadExecutor = Executors.newSingleThreadExecutor()
    private val lock = Any()

    // Tout accès à library / downloaded se fait sous synchronized(lock).
    private val library = LinkedHashMap<String, JSONObject>()
    private val downloaded = HashSet<String>()

    private val downloading = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)

    private var documentStartCapture = false

    @Volatile
    private var onSunoPage = false
    private var treeUri: Uri? = null
    private var pendingDownloadAfterPick = false
    private var pendingIds: Set<String>? = null
    private val selectedIds = HashSet<String>()

    @Volatile
    private var lastRunReport = ""

    private val exportRequestCode = 5101
    private val folderRequestCode = 5102

    private val prefs by lazy { getSharedPreferences("suno_inspector", MODE_PRIVATE) }
    private val sunoDir: File get() = File(filesDir, "suno")
    private val libraryFile: File get() = File(sunoDir, "library.json")
    private val downloadedFile: File get() = File(sunoDir, "downloaded.txt")

    private val uuidRegex =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs.getString("tree_uri", null)?.let { treeUri = Uri.parse(it) }
        ioExecutor.execute { loadState() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
                insets
            }
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
        }

        fun makeButton(label: String, onClick: () -> Unit): Button =
            Button(this).apply {
                text = label
                textSize = 12f
                isAllCaps = false
                setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

        val infoButton = makeButton("Infos") { showDiagnostic() }
        val chooseButton = makeButton("Choisir") { openPicker() }
        downloadButton = makeButton("Tout télécharger") { onDownloadClicked() }
        val exportButton = makeButton("Export") { exportLibrary() }
        controls.addView(infoButton)
        controls.addView(chooseButton)
        controls.addView(downloadButton)
        controls.addView(exportButton)

        statusView = TextView(this).apply {
            textSize = 12f
            setPadding(24, 8, 24, 8)
            visibility = View.GONE
        }

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        root.addView(controls)
        root.addView(statusView)
        root.addView(webView)
        setContentView(root)

        setupWebView()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl("https://suno.com/")
        }
    }

    // ------------------------------------------------------------------
    // WebView
    // ------------------------------------------------------------------

    private fun isSunoHost(host: String?): Boolean =
        host != null && (host == "suno.com" || host.endsWith(".suno.com"))

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            // Certains fournisseurs de connexion (Google) refusent les WebView : on retire le marqueur "wv".
            userAgentString = userAgentString
                .replace("; wv", "")
                .replace(Regex("Version/\\d+\\.\\d+ "), "")
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                onSunoPage = url != null && url.startsWith("https://") && isSunoHost(Uri.parse(url).host)
                // Solution de repli si le script de démarrage n'est pas supporté.
                if (!documentStartCapture && url != null && url.startsWith("https://") &&
                    isSunoHost(Uri.parse(url).host)
                ) {
                    view.evaluateJavascript(detectionScript, null)
                }
            }
        }

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Toast.makeText(
                this,
                "Ce WebView ne supporte pas la capture. Mets à jour « Android System WebView ».",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val origins = setOf("https://suno.com", "https://*.suno.com")

        WebViewCompat.addWebMessageListener(
            webView,
            "SunoInspector",
            origins,
            object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(
                    view: WebView,
                    message: WebMessageCompat,
                    sourceOrigin: Uri,
                    isMainFrame: Boolean,
                    replyProxy: JavaScriptReplyProxy
                ) {
                    if (!isMainFrame) return
                    if (sourceOrigin.scheme != "https") return
                    if (!isSunoHost(sourceOrigin.host)) return
                    val data = message.data ?: return
                    if (data.startsWith("DLC|") || data.startsWith("DLD|") || data.startsWith("DLE|")) {
                        handleDownloadMessage(data)
                        return
                    }
                    ingest(data)
                }
            }
        )

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, detectionScript, origins)
            documentStartCapture = true
        }
    }

    /**
     * Script injecté dans les pages suno.com : il observe (sans rien modifier) les réponses JSON
     * qui contiennent des morceaux (audio_url / media_urls) et les transmet à l'application.
     */
    private val detectionScript = """
        (function () {
          if (window.__sunoInspectorInstalled) return;
          window.__sunoInspectorInstalled = true;

          var hostOk = /(^|\.)suno\.(com|ai)${'$'}/;

          function handle(url, status, contentType, body) {
            try {
              var u = new URL(url, location.href);
              if (!hostOk.test(u.hostname)) return;
              if (Number(status) !== 200) return;
              if (String(contentType || '').toLowerCase().indexOf('application/json') === -1) return;
              if (typeof body !== 'string' || body.length === 0 || body.length > 4000000) return;
              if (body.indexOf('audio_url') === -1 && body.indexOf('media_urls') === -1) return;
              if (!window.SunoInspector) return;
              window.SunoInspector.postMessage(body);
            } catch (e) {}
          }

          function post(msg) {
            try { if (window.SunoInspector) window.SunoInspector.postMessage(msg); } catch (e) {}
          }

          // Téléchargement réalisé par la page elle-même (mêmes en-têtes et même réseau que le navigateur).
          window.__sunoDownload = function (id, url) {
            fetch(url).catch(function () {
              return fetch(url, { credentials: 'include' });
            }).then(function (r) {
              if (!r.ok) { post('DLE|' + id + '|HTTP ' + r.status); return null; }
              return r.arrayBuffer();
            }).then(function (buf) {
              if (!buf) return;
              var bytes = new Uint8Array(buf);
              var CH = 600000;
              for (var i = 0; i < bytes.length; i += CH) {
                var sub = bytes.subarray(i, Math.min(i + CH, bytes.length));
                var bin = '';
                for (var j = 0; j < sub.length; j += 8192) {
                  bin += String.fromCharCode.apply(null, sub.subarray(j, Math.min(j + 8192, sub.length)));
                }
                post('DLC|' + id + '|' + btoa(bin));
              }
              post('DLD|' + id + '|' + bytes.length);
            }).catch(function (e) {
              post('DLE|' + id + '|' + e);
            });
          };

          var originalFetch = window.fetch;
          if (originalFetch) {
            window.fetch = function () {
              var args = arguments;
              var request = args[0];
              var url = (request && request.url) ? request.url : String(request);
              return originalFetch.apply(this, args).then(function (response) {
                try {
                  var ct = response.headers.get('content-type') || '';
                  if (response.status === 200 && ct.toLowerCase().indexOf('application/json') !== -1) {
                    response.clone().text().then(function (body) {
                      handle(response.url || url, response.status, ct, body);
                    }).catch(function () {});
                  }
                } catch (e) {}
                return response;
              });
            };
          }

          var originalOpen = XMLHttpRequest.prototype.open;
          XMLHttpRequest.prototype.open = function (method, url) {
            this.__sunoUrl = url;
            return originalOpen.apply(this, arguments);
          };

          var originalSend = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.send = function () {
            var xhr = this;
            xhr.addEventListener('load', function () {
              try {
                var ct = xhr.getResponseHeader('content-type') || '';
                if (xhr.status === 200 && ct.toLowerCase().indexOf('application/json') !== -1 &&
                    (xhr.responseType === '' || xhr.responseType === 'text')) {
                  handle(xhr.responseURL || xhr.__sunoUrl, xhr.status, ct, xhr.responseText);
                }
              } catch (e) {}
            });
            return originalSend.apply(this, arguments);
          };
        })();
    """.trimIndent()

    // ------------------------------------------------------------------
    // Bibliothèque (détection, stockage)
    // ------------------------------------------------------------------

    private fun str(o: JSONObject?, key: String): String =
        if (o == null || o.isNull(key)) "" else o.optString(key, "")

    private fun loadState() {
        synchronized(lock) {
            try {
                if (libraryFile.exists()) {
                    val arr = JSONArray(libraryFile.readText())
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = str(o, "id")
                        if (id.isNotBlank()) library[id] = o
                    }
                }
            } catch (_: Exception) {
            }
            try {
                if (downloadedFile.exists()) {
                    downloadedFile.readLines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .forEach { downloaded.add(it) }
                }
            } catch (_: Exception) {
            }
        }
    }

    /** À appeler sous synchronized(lock). */
    private fun persistLibrary() {
        try {
            sunoDir.mkdirs()
            val arr = JSONArray()
            library.values.forEach { arr.put(it) }
            val text = arr.toString()
            val tmp = File(sunoDir, "library.json.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(libraryFile)) {
                libraryFile.writeText(text)
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun collectClips(node: Any?, out: MutableList<JSONObject>, depth: Int) {
        if (depth > 8) return
        when (node) {
            is JSONObject -> {
                val id = str(node, "id")
                if (uuidRegex.matches(id) && (node.has("audio_url") || node.has("media_urls"))) {
                    out.add(node)
                } else {
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        collectClips(node.opt(keys.next()), out, depth + 1)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectClips(node.opt(i), out, depth + 1)
                }
            }
        }
    }

    private fun toEntry(clip: JSONObject): JSONObject {
        val meta = clip.optJSONObject("metadata")
        return JSONObject()
            .put("id", str(clip, "id"))
            .put("title", str(clip, "title"))
            .put("created_at", str(clip, "created_at"))
            .put("user_id", str(clip, "user_id"))
            .put("handle", str(clip, "handle"))
            .put("display_name", str(clip, "display_name"))
            .put("model", str(clip, "model_name"))
            .put("status", str(clip, "status"))
            .put("is_trashed", clip.optBoolean("is_trashed", false))
            .put("audio_url", str(clip, "audio_url"))
            .put("image_url", str(clip, "image_url"))
            .put("lyrics", str(meta, "prompt"))
            .put("tags", str(meta, "tags"))
    }

    /** Fusionne : une valeur non vide déjà connue n'est pas écrasée par une valeur vide. */
    private fun merge(old: JSONObject?, new: JSONObject): JSONObject {
        if (old == null) return new
        val keys = new.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = new.opt(k)
            if (v is String && v.isEmpty() && str(old, k).isNotEmpty()) continue
            old.put(k, v)
        }
        return old
    }

    private fun ingest(body: String) {
        ioExecutor.execute {
            try {
                if (body.length > 4_000_000) return@execute
                val root = JSONTokener(body).nextValue()
                val clips = ArrayList<JSONObject>()
                collectClips(root, clips, 0)
                if (clips.isEmpty()) return@execute

                val total = synchronized(lock) {
                    for (clip in clips) {
                        val entry = toEntry(clip)
                        val id = str(entry, "id")
                        library[id] = merge(library[id], entry)
                    }
                    persistLibrary()
                    library.size
                }

                if (!downloading.get()) {
                    setStatus("Bibliothèque : $total morceaux détectés")
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun setStatus(text: String) {
        runOnUiThread {
            statusView.text = text
            statusView.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun toast(text: String) {
        runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    }

    // ------------------------------------------------------------------
    // Propriétaires (comptes) : on ne télécharge qu'un compte à la fois
    // ------------------------------------------------------------------

    private data class Owner(val userId: String, val label: String, val count: Int)

    /** À appeler sous synchronized(lock). */
    private fun ownerGroups(): List<Owner> =
        library.values
            .filter { !it.optBoolean("is_trashed", false) }
            .groupBy { str(it, "user_id") }
            .map { (uid, list) ->
                val named = list.firstOrNull { str(it, "handle").isNotBlank() } ?: list.first()
                val label = str(named, "handle")
                    .ifBlank { str(named, "display_name") }
                    .ifBlank { uid.take(8) }
                    .ifBlank { "inconnu" }
                Owner(uid, label, list.size)
            }
            .sortedByDescending { it.count }

    // ------------------------------------------------------------------
    // Diagnostic
    // ------------------------------------------------------------------

    private fun showDiagnostic() {
        ioExecutor.execute {
            val report = synchronized(lock) {
                val total = library.size
                val trashed = library.values.count { it.optBoolean("is_trashed", false) }
                val withLyrics = library.values.count { str(it, "lyrics").isNotBlank() }
                val owners = ownerGroups()
                val pending = library.values.count {
                    !it.optBoolean("is_trashed", false) && !downloaded.contains(str(it, "id"))
                }
                buildString {
                    appendLine("Morceaux détectés : $total")
                    appendLine("Dans la corbeille : $trashed")
                    appendLine("Avec paroles : $withLyrics")
                    appendLine("Déjà téléchargés : ${downloaded.size}")
                    appendLine("Restant à télécharger : $pending")
                    appendLine()
                    if (owners.isNotEmpty()) {
                        appendLine("Comptes vus :")
                        owners.take(5).forEach { appendLine("• ${it.label} (${it.count})") }
                        appendLine()
                    }
                    appendLine("Dossier : " + (treeUri?.lastPathSegment ?: "non choisi"))
                    appendLine()
                    if (lastRunReport.isNotBlank()) {
                        appendLine("Dernier téléchargement :")
                        appendLine(lastRunReport)
                        appendLine()
                    }
                    append(
                        "Astuce : ouvre ta bibliothèque sur le site et fais défiler la liste " +
                            "jusqu'en bas pour que tous tes morceaux soient détectés."
                    )
                }
            }
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("Suno Inspector")
                    .setMessage(report)
                    .setPositiveButton("Fermer", null)
                    .setNeutralButton("Dossier") { _, _ ->
                        pendingDownloadAfterPick = false
                        pickFolder()
                    }
                    .setNegativeButton("Effacer") { _, _ -> confirmClear() }
                    .show()
            }
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Effacer la bibliothèque détectée ?")
            .setMessage(
                "La liste des morceaux détectés et l'historique des téléchargements seront supprimés " +
                    "(les fichiers déjà enregistrés ne sont pas touchés)."
            )
            .setPositiveButton("Effacer") { _, _ ->
                ioExecutor.execute {
                    synchronized(lock) {
                        library.clear()
                        downloaded.clear()
                        libraryFile.delete()
                        downloadedFile.delete()
                    }
                    setStatus("")
                    toast("Bibliothèque effacée")
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ------------------------------------------------------------------
    // Dossier de destination
    // ------------------------------------------------------------------

    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, folderRequestCode)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data

        when (requestCode) {
            folderRequestCode -> {
                if (resultCode != RESULT_OK || uri == null) {
                    pendingDownloadAfterPick = false
                    pendingIds = null
                    return
                }
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
                val changed = treeUri != uri
                treeUri = uri
                prefs.edit().putString("tree_uri", uri.toString()).apply()
                if (changed) {
                    // Nouveau dossier : on repart de zéro pour l'historique des téléchargements.
                    ioExecutor.execute {
                        synchronized(lock) {
                            downloaded.clear()
                            downloadedFile.delete()
                        }
                    }
                }
                toast("Dossier enregistré")
                val ids = pendingIds
                if (ids != null) {
                    pendingIds = null
                    pendingDownloadAfterPick = false
                    beginDownload(null, ids)
                } else if (pendingDownloadAfterPick) {
                    pendingDownloadAfterPick = false
                    onDownloadClicked()
                }
            }

            exportRequestCode -> {
                if (resultCode != RESULT_OK || uri == null) return
                ioExecutor.execute {
                    try {
                        synchronized(lock) { persistLibrary() }
                        contentResolver.openOutputStream(uri)?.use { out ->
                            libraryFile.inputStream().use { it.copyTo(out) }
                        }
                        toast("Export JSON enregistré")
                    } catch (e: Exception) {
                        toast("Échec de l'export : ${e.message}")
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Export JSON (nom basé sur la date/heure de lancement de l'export)
    // ------------------------------------------------------------------

    private fun exportLibrary() {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "suno_library_$stamp.json")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, exportRequestCode)
    }

    // ------------------------------------------------------------------
    // Téléchargement
    // ------------------------------------------------------------------

    private fun onDownloadClicked() {
        if (downloading.get()) {
            cancelRequested.set(true)
            setStatus("Arrêt en cours…")
            return
        }
        if (treeUri == null) {
            pendingDownloadAfterPick = true
            pickFolder()
            return
        }
        ioExecutor.execute {
            val owners = synchronized(lock) { ownerGroups() }
            when {
                owners.isEmpty() -> toast(
                    "Aucun morceau détecté. Ouvre ta bibliothèque sur le site et fais défiler la liste."
                )
                owners.size == 1 -> beginDownload(owners[0].userId, null)
                else -> runOnUiThread { chooseOwner(owners) }
            }
        }
    }

    private fun chooseOwner(owners: List<Owner>) {
        val labels = owners.map { "${it.label} — ${it.count} morceaux" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Quel compte télécharger ?")
            .setItems(labels) { _, which -> beginDownload(owners[which].userId, null) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun beginDownload(ownerId: String?, ids: Set<String>?) {
        downloadExecutor.execute { runDownload(ownerId, ids) }
    }

    private fun runDownload(ownerId: String?, ids: Set<String>?) {
        if (!downloading.compareAndSet(false, true)) return
        cancelRequested.set(false)

        val tree = treeUri
        if (tree == null) {
            downloading.set(false)
            return
        }

        val startMs = System.currentTimeMillis()
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val stampFmt = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault())
        val runName = "Suno_" + stampFmt.format(Date(startMs))

        val todo: List<JSONObject> = synchronized(lock) {
            library.values.filter { e ->
                !e.optBoolean("is_trashed", false) &&
                    (str(e, "status").isEmpty() || str(e, "status") == "complete") &&
                    if (ids != null) {
                        // Sélection manuelle : on télécharge même si déjà fait.
                        ids.contains(str(e, "id"))
                    } else {
                        str(e, "user_id") == ownerId && !downloaded.contains(str(e, "id"))
                    }
            }
        }

        if (todo.isEmpty()) {
            downloading.set(false)
            setStatus("Rien de nouveau à télécharger.")
            return
        }

        runOnUiThread {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            downloadButton.text = "Arrêter"
        }

        // Estimation initiale : environ 6 s par morceau, affinée au fil des téléchargements.
        val estimatedEnd = startMs + todo.size * 6000L
        setStatus(
            "Début ${timeFmt.format(Date(startMs))} • fin estimée ${timeFmt.format(Date(estimatedEnd))} • 0/${todo.size}"
        )

        var ok = 0
        var fail = 0
        var errorMessage: String? = null
        val failures = ArrayList<String>()
        var runDirUri: Uri? = null

        try {
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            val runDir = try {
                DocumentsContract.createDocument(
                    contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, runName
                )
            } catch (_: Exception) {
                null
            }

            if (runDir == null) {
                errorMessage = "Impossible de créer le dossier de destination. Choisis un autre dossier (bouton Infos > Dossier)."
            } else {
                runDirUri = runDir
                for ((index, clip) in todo.withIndex()) {
                    if (cancelRequested.get()) break

                    val id = str(clip, "id")
                    val error = downloadClip(clip, runDir)
                    if (error == null) {
                        ok++
                        markDownloaded(id)
                    } else {
                        fail++
                        failures.add(str(clip, "title").ifBlank { id.take(8) } + " → " + error)
                    }

                    val done = index + 1
                    val now = System.currentTimeMillis()
                    val remaining = (now - startMs) / done * (todo.size - done)
                    setStatus(
                        "$done/${todo.size} • fin estimée ${timeFmt.format(Date(now + remaining))} • échecs : $fail"
                    )
                    Thread.sleep(300)
                }
            }
        } catch (e: Exception) {
            errorMessage = "Erreur : ${e.message}"
        } finally {
            val stopped = cancelRequested.get()
            downloading.set(false)
            cancelRequested.set(false)
            val endMs = System.currentTimeMillis()
            val logDir = runDirUri
            if (failures.isNotEmpty() && logDir != null) {
                try {
                    val log = failures.joinToString("\n")
                    writeDoc(logDir, "echecs.txt", "text/plain") { out ->
                        out.write(log.toByteArray(Charsets.UTF_8))
                    }
                } catch (_: Exception) {
                }
            }
            val summary = errorMessage
                ?: ((if (stopped) "Arrêté" else "Terminé") +
                    " à ${timeFmt.format(Date(endMs))} (début ${timeFmt.format(Date(startMs))}) : " +
                    "$ok téléchargés, $fail échecs. Dossier : $runName")
            lastRunReport = summary +
                if (failures.isNotEmpty()) "\nCauses (3 premières) :\n" + failures.take(3).joinToString("\n") else ""
            runOnUiThread {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                downloadButton.text = "Tout télécharger"
            }
            setStatus(summary)
        }
    }

    private fun markDownloaded(id: String) {
        synchronized(lock) {
            downloaded.add(id)
            try {
                sunoDir.mkdirs()
                downloadedFile.appendText(id + "\n")
            } catch (_: Exception) {
            }
        }
    }

    /** Retourne null si OK, sinon le motif de l'échec. */
    private fun downloadClip(clip: JSONObject, runDir: Uri): String? {
        val id = str(clip, "id")
        val title = str(clip, "title")
        val safe = title
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim()
            .take(80)
            .ifBlank { "Sans titre" }
        val base = "$safe [${id.take(8)}]"
        val temp = File(cacheDir, "dl_$id.mp3")

        try {
            // Plusieurs adresses possibles : celle fournie par le site d'abord, puis le CDN, puis audiopipe.
            val urls = ArrayList<String>()
            val alt = str(clip, "audio_url")
            if (alt.startsWith("https://")) urls.add(alt)
            urls.add("https://cdn1.suno.ai/$id.mp3")
            urls.add("https://audiopipe.suno.ai/?item_id=$id")

            val errors = ArrayList<String>()
            var ok = false
            val candidates = urls.distinct()
            for (u in candidates) {
                val err = fetchTo(u, temp)
                if (err == null) {
                    ok = true
                    break
                }
                errors.add(Uri.parse(u).host + ": " + err)
            }
            if (!ok) {
                // Dernier recours : téléchargement fait par la page suno.com elle-même.
                for (u in candidates.take(2)) {
                    if (cancelRequested.get()) break
                    val err = fetchViaPage(id, u, temp)
                    if (err == null) {
                        ok = true
                        break
                    }
                    errors.add(err)
                }
            }
            if (!ok) return errors.joinToString(" | ")

            val saved = writeDoc(runDir, "$base.mp3", "audio/mpeg") { out ->
                temp.inputStream().use { it.copyTo(out) }
            }
            if (!saved) return "écriture dans le dossier impossible"

            val lyrics = str(clip, "lyrics")
            val tags = str(clip, "tags")
            if (lyrics.isNotBlank() || tags.isNotBlank()) {
                val text = buildString {
                    appendLine("Titre : $title")
                    if (tags.isNotBlank()) appendLine("Style : $tags")
                    if (str(clip, "created_at").isNotBlank()) appendLine("Créé le : ${str(clip, "created_at")}")
                    appendLine("ID : $id")
                    if (lyrics.isNotBlank()) {
                        appendLine()
                        appendLine("Paroles :")
                        appendLine(lyrics)
                    }
                }
                writeDoc(runDir, "$base.txt", "text/plain") { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                }
            }
            return null
        } catch (e: Exception) {
            return e.javaClass.simpleName + " " + (e.message ?: "")
        } finally {
            temp.delete()
        }
    }

    private fun writeDoc(parent: Uri, name: String, mime: String, writer: (OutputStream) -> Unit): Boolean {
        val doc = DocumentsContract.createDocument(contentResolver, parent, mime, name) ?: return false
        val out = contentResolver.openOutputStream(doc) ?: return false
        out.use { writer(it) }
        return true
    }

    /** Retourne null si OK, sinon le motif de l'échec (ex. "HTTP 403 [cloudflare]"). */
    private fun fetchTo(url: String, dest: File): String? {
        var conn: HttpURLConnection? = null
        try {
            val u = URL(url)
            if (u.protocol != "https") return "adresse non https"
            val host = u.host
            if (!(host == "suno.ai" || host.endsWith(".suno.ai") || host == "suno.com" || host.endsWith(".suno.com"))) {
                return "hôte non autorisé"
            }
            conn = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 60000
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
                )
                setRequestProperty("Referer", "https://suno.com/")
                setRequestProperty("Origin", "https://suno.com")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
                setRequestProperty("Range", "bytes=0-")
                setRequestProperty("Sec-Fetch-Dest", "audio")
                setRequestProperty("Sec-Fetch-Mode", "no-cors")
                setRequestProperty("Sec-Fetch-Site", "cross-site")
                try {
                    val cookie = CookieManager.getInstance().getCookie(url)
                    if (!cookie.isNullOrEmpty()) setRequestProperty("Cookie", cookie)
                } catch (_: Exception) {
                }
            }
            val code = conn.responseCode
            if (code != 200 && code != 206) {
                val server = conn.getHeaderField("Server") ?: ""
                val mitigated = conn.getHeaderField("cf-mitigated") ?: ""
                val body: String = try {
                    conn.errorStream?.bufferedReader()?.use { it.readText().take(120) } ?: ""
                } catch (_: Exception) {
                    ""
                }
                return "HTTP $code" +
                    (if (server.isNotEmpty()) " [$server]" else "") +
                    (if (mitigated.isNotEmpty()) " mitigated=$mitigated" else "") +
                    (if (body.isNotBlank()) " « " + body.replace(Regex("\\s+"), " ").trim() + " »" else "")
            }
            conn.inputStream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            val size = dest.length()
            return if (size > 1024) null else "fichier trop petit ($size o)"
        } catch (e: Exception) {
            return e.javaClass.simpleName + " " + (e.message ?: "")
        } finally {
            conn?.disconnect()
        }
    }

    // ------------------------------------------------------------------
    // Téléchargement réalisé par la page suno.com (dernier recours)
    // ------------------------------------------------------------------

    private class JsDownload(val file: File) {
        val latch = CountDownLatch(1)

        @Volatile
        var error: String? = null
    }

    private val jsDownloads = ConcurrentHashMap<String, JsDownload>()

    private fun handleDownloadMessage(data: String) {
        val parts = data.split("|", limit = 3)
        if (parts.size < 3) return
        val kind = parts[0]
        val id = parts[1]
        val payload = parts[2]
        val job = jsDownloads[id] ?: return
        // Même exécuteur que le reste : l'ordre des morceaux de fichier est conservé.
        ioExecutor.execute {
            try {
                when (kind) {
                    "DLC" -> job.file.appendBytes(Base64.decode(payload, Base64.DEFAULT))
                    "DLD" -> job.latch.countDown()
                    "DLE" -> {
                        job.error = payload
                        job.latch.countDown()
                    }
                }
            } catch (e: Exception) {
                job.error = "écriture : " + (e.message ?: "")
                job.latch.countDown()
            }
        }
    }

    private fun fetchViaPage(id: String, url: String, dest: File): String? {
        if (!onSunoPage) return "page : ouvre une page suno.com dans l'app"
        val job = JsDownload(dest)
        dest.delete()
        jsDownloads[id] = job
        try {
            val js = "window.__sunoDownload(" + JSONObject.quote(id) + "," + JSONObject.quote(url) + ");"
            runOnUiThread { webView.evaluateJavascript(js, null) }
            if (!job.latch.await(120, TimeUnit.SECONDS)) return "page : délai dépassé"
            val err = job.error
            if (err != null) return "page : $err"
            val size = dest.length()
            return if (size > 1024) null else "page : fichier trop petit ($size o)"
        } finally {
            jsDownloads.remove(id)
        }
    }

    // ------------------------------------------------------------------
    // Sélection des chansons
    // ------------------------------------------------------------------

    private data class Pick(val id: String, val title: String, val owner: String, val created: String, val done: Boolean)

    private fun openPicker() {
        if (downloading.get()) {
            toast("Un téléchargement est en cours.")
            return
        }
        ioExecutor.execute {
            val picks: List<Pick> = synchronized(lock) {
                library.values
                    .filter {
                        !it.optBoolean("is_trashed", false) &&
                            (str(it, "status").isEmpty() || str(it, "status") == "complete")
                    }
                    .sortedByDescending { str(it, "created_at") }
                    .map {
                        val id = str(it, "id")
                        Pick(
                            id,
                            str(it, "title").ifBlank { "Sans titre" },
                            str(it, "handle").ifBlank { str(it, "display_name") },
                            str(it, "created_at").take(10),
                            downloaded.contains(id)
                        )
                    }
            }
            if (picks.isEmpty()) {
                toast("Aucun morceau détecté. Ouvre ta bibliothèque sur le site et fais défiler la liste.")
                return@execute
            }
            runOnUiThread { showPicker(picks) }
        }
    }

    private fun showPicker(all: List<Pick>) {
        val multiOwner = all.map { it.owner }.distinct().size > 1
        val shown = ArrayList<Pick>(all)

        fun label(p: Pick): String {
            val parts = ArrayList<String>()
            if (p.created.isNotBlank()) parts.add(p.created)
            if (multiOwner && p.owner.isNotBlank()) parts.add("@" + p.owner)
            if (p.done) parts.add("✓ déjà téléchargé")
            return p.title + if (parts.isEmpty()) "" else "\n" + parts.joinToString(" • ")
        }

        val pickAdapter = object : ArrayAdapter<Pick>(this, android.R.layout.simple_list_item_multiple_choice, shown) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val item = getItem(position)
                if (v is CheckedTextView && item != null) {
                    v.text = label(item)
                    v.textSize = 14f
                    v.isChecked = selectedIds.contains(item.id)
                }
                return v
            }
        }

        val search = EditText(this).apply {
            hint = "Rechercher un titre…"
            setSingleLine(true)
        }
        val list = ListView(this).apply {
            this.adapter = pickAdapter
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.55).toInt()
            )
            setOnItemClickListener { _, _, position, _ ->
                val item = pickAdapter.getItem(position) ?: return@setOnItemClickListener
                if (!selectedIds.add(item.id)) selectedIds.remove(item.id)
                pickAdapter.notifyDataSetChanged()
                updateButtons()
            }
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
            addView(search)
            addView(list)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Choisir les chansons (${all.size})")
            .setView(container)
            .setPositiveButton("Télécharger", null)
            .setNeutralButton("Tout cocher", null)
            .setNegativeButton("Fermer", null)
            .create()

        fun refreshList() {
            val q = search.text.toString().trim().lowercase()
            shown.clear()
            shown.addAll(
                if (q.isEmpty()) all
                else all.filter { it.title.lowercase().contains(q) || it.owner.lowercase().contains(q) }
            )
            pickAdapter.notifyDataSetChanged()
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refreshList()
            }
        })

        dialog.show()

        // Ces boutons ne ferment pas la boîte de dialogue : on remplace leurs actions par défaut.
        val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)

        fun updateButtonsLocal() {
            positive.text = "Télécharger (${selectedIds.size})"
            positive.isEnabled = selectedIds.isNotEmpty()
            val allShownSelected = shown.isNotEmpty() && shown.all { selectedIds.contains(it.id) }
            neutral.text = if (allShownSelected) "Tout décocher" else "Tout cocher"
        }
        pickerUpdater = { updateButtonsLocal() }
        updateButtonsLocal()

        neutral.setOnClickListener {
            val allShownSelected = shown.isNotEmpty() && shown.all { selectedIds.contains(it.id) }
            if (allShownSelected) shown.forEach { selectedIds.remove(it.id) }
            else shown.forEach { selectedIds.add(it.id) }
            pickAdapter.notifyDataSetChanged()
            updateButtonsLocal()
        }

        positive.setOnClickListener {
            val ids = HashSet(selectedIds)
            selectedIds.clear()
            if (ids.isEmpty()) return@setOnClickListener
            dialog.dismiss()
            if (treeUri == null) {
                pendingIds = ids
                pickFolder()
            } else {
                beginDownload(null, ids)
            }
        }

        dialog.setOnDismissListener { pickerUpdater = null }
    }

    private var pickerUpdater: (() -> Unit)? = null

    private fun updateButtons() {
        pickerUpdater?.invoke()
    }

    // ------------------------------------------------------------------
    // Cycle de vie
    // ------------------------------------------------------------------

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        cancelRequested.set(true)
        webView.destroy()
        ioExecutor.shutdown()
        downloadExecutor.shutdown()
        super.onDestroy()
    }
}
