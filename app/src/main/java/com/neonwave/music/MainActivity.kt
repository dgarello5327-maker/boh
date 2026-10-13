package com.neonwave.music

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {
    data class Track(val id: String, val title: String, val artist: String, val url: String = "", val uri: Uri? = null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var player: MediaController? = null
    private val tracks = mutableListOf<Track>()
    private val favorites = linkedMapOf<String, Track>()
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var results: LinearLayout
    private lateinit var heading: TextView
    private lateinit var hint: TextView
    private lateinit var playButton: Button
    private lateinit var progress: SeekBar

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (!uris.isNullOrEmpty()) {
            val local = uris.map { uri ->
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "File audio"
                Track(uri.toString(), name.substringBeforeLast('.'), "File locale", uri.toString(), uri)
            }
            tracks.clear()
            tracks.addAll(local)
            heading.text = "I TUOI FILE AUDIO"
            hint.text = "Seleziona un file per ascoltarlo."
            renderTracks(local)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        search = findViewById(R.id.search)
        title = findViewById(R.id.trackTitle)
        artist = findViewById(R.id.trackArtist)
        results = findViewById(R.id.results)
        heading = findViewById(R.id.listHeading)
        hint = findViewById(R.id.resultsHint)
        playButton = findViewById(R.id.playButton)
        progress = findViewById(R.id.progress)

        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({
            runCatching { controllerFuture?.get() }.onSuccess { connected ->
                player = connected
                connected?.addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = updateNowPlaying()
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        playButton.text = if (isPlaying) "Ⅱ PAUSA" else "▶ RIPRODUCI"
                    }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) updateNowPlaying()
                    }
                })
                updateNowPlaying()
            }.onFailure { status.text = "Impossibile avviare il lettore" }
        }, ContextCompat.getMainExecutor(this))

        findViewById<Button>(R.id.searchButton).setOnClickListener { searchArchive(search.text.toString().trim()) }
        findViewById<Button>(R.id.discoverButton).setOnClickListener { search.setText("music"); searchArchive("music") }
        findViewById<Button>(R.id.localButton).setOnClickListener { pickAudio.launch(arrayOf("audio/*")) }
        findViewById<Button>(R.id.favoritesButton).setOnClickListener {
            heading.text = "I TUOI PREFERITI"
            hint.text = if (favorites.isEmpty()) "Non hai ancora aggiunto brani ai preferiti." else "I brani che hai salvato."
            renderTracks(favorites.values.toList())
        }
        playButton.setOnClickListener {
            val p = player
            if (p == null) return@setOnClickListener
            if (p.isPlaying) p.pause() else if (p.mediaItemCount > 0) p.play()
            else Toast.makeText(this, "Scegli prima un brano", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.previousButton).setOnClickListener { player?.seekToPreviousMediaItem() }
        findViewById<Button>(R.id.nextButton).setOnClickListener { player?.seekToNextMediaItem() }
        findViewById<Button>(R.id.favoriteCurrent).setOnClickListener {
            val item = player?.currentMediaItem ?: return@setOnClickListener
            val id = item.mediaId.ifBlank { item.localConfiguration?.uri.toString() }
            val track = tracks.firstOrNull { it.id == id } ?: Track(
                id, item.mediaMetadata.title?.toString() ?: "Brano",
                item.mediaMetadata.artist?.toString() ?: "Artista",
                item.localConfiguration?.uri.toString()
            )
            if (favorites.containsKey(id)) {
                favorites.remove(id)
                Toast.makeText(this, "Rimosso dai preferiti", Toast.LENGTH_SHORT).show()
            } else {
                favorites[id] = track
                Toast.makeText(this, "Aggiunto ai preferiti", Toast.LENGTH_SHORT).show()
            }
        }
        progress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                val p = player
                if (fromUser && p != null && p.duration > 0) p.seekTo(p.duration * value / 1000L)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        searchArchive("music")
    }

    private fun searchArchive(query: String) {
        if (query.isBlank()) { status.text = "Inserisci un titolo o un artista"; return }
        status.text = "Ricerca in corso…"
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    val q = URLEncoder.encode("($query) AND mediatype:audio", "UTF-8")
                    val url = URL("https://archive.org/advancedsearch.php?q=" + q + "&fl[]=identifier,title,creator&rows=50&page=1&output=json")
                    val connection = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 12000
                        readTimeout = 15000
                        setRequestProperty("User-Agent", "NeonWaveMusic/1.0")
                    }
                    try {
                        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                        val docs = json.getJSONObject("response").getJSONArray("docs")
                        buildList {
                            for (i in 0 until docs.length()) {
                                val doc = docs.getJSONObject(i)
                                val id = doc.optString("identifier")
                                if (id.isNotBlank()) add(Track(id, doc.optString("title", "Senza titolo"), doc.optString("creator", "Artista sconosciuto")))
                            }
                        }
                    } finally { connection.disconnect() }
                }.getOrDefault(emptyList())
            }
            tracks.clear()
            tracks.addAll(found)
            heading.text = "RISULTATI DI RICERCA"
            hint.text = if (found.isEmpty()) "Nessun risultato. Prova un'altra ricerca." else "Registrazioni gratuite su Internet Archive. Tocca un risultato per caricarlo."
            status.text = if (found.isEmpty()) "Nessun risultato disponibile" else found.size.toString() + " risultati trovati"
            renderTracks(found)
        }
    }

    private fun resolveAndPlay(track: Track, index: Int) {
        val p = player ?: run {
            Toast.makeText(this, "Lettore in avvio, riprova tra un secondo", Toast.LENGTH_SHORT).show()
            return
        }
        if (track.uri != null) {
            val local = tracks.filter { it.uri != null }
            playQueue(local, local.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
            return
        }
        if (track.url.isNotBlank()) {
            val queue = tracks.filter { it.url.isNotBlank() }
            playQueue(queue, queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
            return
        }
        status.text = "Caricamento: " + track.title
        scope.launch {
            val audioUrl = withContext(Dispatchers.IO) {
                runCatching {
                    val url = URL("https://archive.org/metadata/" + URLEncoder.encode(track.id, "UTF-8"))
                    val connection = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 12000; readTimeout = 15000 }
                    try {
                        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                        val files = json.optJSONArray("files") ?: return@runCatching null
                        var chosen: String? = null
                        for (i in 0 until files.length()) {
                            val f = files.optJSONObject(i) ?: continue
                            val name = f.optString("name")
                            val format = f.optString("format").lowercase()
                            if ((name.endsWith(".mp3", true) || name.endsWith(".ogg", true) || name.endsWith(".m4a", true) || name.endsWith(".flac", true)) &&
                                (format.contains("mp3") || format.contains("ogg") || format.contains("aac") || format.contains("flac") || format.contains("audio"))) {
                                chosen = name
                                break
                            }
                        }
                        chosen?.let { "https://archive.org/download/" + track.id + "/" + Uri.encode(it) }
                    } finally { connection.disconnect() }
                }.getOrNull()
            }
            if (audioUrl == null) {
                status.text = "Audio non disponibile per questo elemento"
                Toast.makeText(this@MainActivity, "Prova un altro risultato", Toast.LENGTH_SHORT).show()
            } else {
                val actual = track.copy(url = audioUrl)
                val idx = tracks.indexOfFirst { it.id == track.id }
                if (idx >= 0) tracks[idx] = actual
                val queue = tracks.filter { it.url.isNotBlank() }
                playQueue(queue, queue.indexOfFirst { it.id == actual.id }.coerceAtLeast(0))
            }
        }
    }

    private fun playQueue(queue: List<Track>, index: Int) {
        val p = player ?: return
        val items = queue.map { t ->
            val uri = t.uri ?: Uri.parse(t.url)
            MediaItem.Builder().setMediaId(t.id).setUri(uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).build()).build()
        }
        if (items.isEmpty()) return
        p.setMediaItems(items, index.coerceIn(items.indices), 0L)
        p.prepare()
        p.play()
    }

    private fun renderTracks(list: List<Track>) {
        results.removeAllViews()
        if (list.isEmpty()) {
            results.addView(TextView(this).apply {
                text = "Nessun brano da mostrare."
                setTextColor(0xFFB8AEC7.toInt())
                setPadding(8, 18, 8, 18)
            })
            return
        }
        list.forEachIndexed { index, track ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(14, 10, 8, 10)
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_panel)
            }
            val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            textColumn.addView(TextView(this).apply {
                text = track.title
                textSize = 15f
                setTextColor(0xFFFFFFFF.toInt())
                maxLines = 2
            })
            textColumn.addView(TextView(this).apply {
                text = track.artist
                textSize = 12f
                setTextColor(0xFFB8AEC7.toInt())
                maxLines = 1
            })
            card.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(Button(this).apply {
                text = "▶"
                setTextColor(0xFFFFFFFF.toInt())
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_button)
                setOnClickListener { resolveAndPlay(track, index) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 48))
            results.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8 })
        }
    }

    private fun updateNowPlaying() {
        val p = player ?: return
        val item = p.currentMediaItem ?: return
        title.text = item.mediaMetadata.title ?: "Brano"
        artist.text = item.mediaMetadata.artist ?: "NeonWave Music"
        playButton.text = if (p.isPlaying) "Ⅱ PAUSA" else "▶ RIPRODUCI"
        status.text = if (p.isPlaying) "In riproduzione" else "In pausa"
        if (p.duration > 0) progress.progress = ((p.currentPosition.toDouble() / p.duration) * 1000).toInt()
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        scope.cancel()
        super.onDestroy()
    }
}
