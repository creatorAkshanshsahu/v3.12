package com.aktv.app

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.util.Base64
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.*
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.drm.*
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.*
import coil.load
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class VH(v: View) : RecyclerView.ViewHolder(v)

class MainActivity : AppCompatActivity() {
    data class Channel(val id: String, val name: String, val category: String, val logo: String)
    data class Site(val name: String, val url: String, val c1: Int, val c2: Int)
    sealed class Tile {
        data class Ch(val c: Channel) : Tile()
        data class St(val s: Site) : Tile()
        object Hint : Tile()
    }
    class Row(val title: String, var tiles: List<Tile>)

    // ➜ ADD MORE WEBSITES HERE (name, link, gradient colours)
    private val SITES = listOf(
        Site("Net77", "https://net77.cc/home", 0xFFE50914.toInt(), 0xFF5A0A12.toInt()),
        Site("AethoFlix", "https://www.aethoflix.world/", 0xFF0A84FF.toInt(), 0xFF5E5CE6.toInt())
    )
    // Use the V1 channel API only, as requested.
    private val BASE = "https://livetgtv.lovable.app/api/public/channels"
    private val UA = "Mozilla/5.0 (Linux; Android 9; TV) AppleWebKit/537.36 Chrome/110 Safari/537.36"
    private val io = Executors.newFixedThreadPool(3)
    private val prefs by lazy { getSharedPreferences("aktv", Context.MODE_PRIVATE) }
    private val dp by lazy { resources.displayMetrics.density }
    private val ui = Handler(Looper.getMainLooper())
    private val fRegular by lazy { ResourcesCompat.getFont(this, R.font.sora_regular) ?: Typeface.DEFAULT }
    private val fSemi by lazy { ResourcesCompat.getFont(this, R.font.sora_semibold) ?: Typeface.DEFAULT_BOLD }

    private var all = listOf<Channel>(); private var shown = listOf<Channel>()
    private var rows = listOf<Row>(); private val inner = HashMap<Int, TileAdapter>()
    private var favs = setOf<String>(); private var heroTile: Tile? = null
    private val accents = HashMap<String, Int>()
    private var tab = 0; private var current = -1; private var retries = 0
    private var player: ExoPlayer? = null
    private var activeSources: List<String> = emptyList()
    private var activeEmbed: String? = null
    private var sourceIndex = 0
    private var activeChannelId: String? = null

    private lateinit var rowsView: RecyclerView; private lateinit var status: TextView
    private lateinit var layer: View; private lateinit var pv: PlayerView; private lateinit var spinner: View
    private lateinit var segLive: TextView; private lateinit var segSites: TextView
    private lateinit var segIndicator: View; private val segPill = GradientDrawable()
    private lateinit var aurora: AuroraView
    private lateinit var heroText: View; private lateinit var heroDot: View; private lateinit var heroBadge: TextView
    private lateinit var heroTitle: TextView; private lateinit var heroChips: LinearLayout
    private lateinit var heroOrb: View; private lateinit var heroLogo: ImageView; private lateinit var heroMono: TextView
    private val orbGlass by lazy { GlassDrawable(dp) }
    private lateinit var infoCard: View; private lateinit var infoLogo: ImageView; private lateinit var infoName: TextView
    private lateinit var infoCat: TextView; private lateinit var playerScrim: View; private lateinit var playerClock: View
    private lateinit var infoHintView: TextView
    private var guideRequestChannel: String? = null
    // channel guide
    private val FAV = "Favorites"
    private lateinit var home: ViewGroup; private var homeFocusMode = 0; private var lastHomeFocus: View? = null
    private lateinit var guide: ViewGroup; private lateinit var guideScrim: View; private lateinit var guideDivider: View
    private lateinit var catList: RecyclerView; private lateinit var chList: RecyclerView
    private lateinit var chCol: View; private lateinit var chHeader: TextView; private lateinit var channelSearch: EditText
    private var guideShown = false; private var shownCat = ""
    private var guideCats = listOf<String>(); private var catCounts = mapOf<String, Int>()
    private var guideCat = ""; private var guideChannels = listOf<Channel>(); private var categoryChannels = listOf<Channel>()
    private val infoGlass by lazy { GlassDrawable(dp, 24f).apply { base = Color.argb(150, 12, 14, 32) } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        rowsView = findViewById(R.id.rows); status = findViewById(R.id.status)
        layer = findViewById(R.id.playerLayer); pv = findViewById(R.id.playerView); spinner = findViewById(R.id.spinner)
        segLive = findViewById(R.id.segLive); segSites = findViewById(R.id.segSites); segIndicator = findViewById(R.id.segIndicator)
        aurora = findViewById(R.id.aurora)
        heroText = findViewById(R.id.heroText); heroDot = findViewById(R.id.heroDot); heroBadge = findViewById(R.id.heroBadge)
        heroTitle = findViewById(R.id.heroTitle); heroChips = findViewById(R.id.heroChips)
        heroOrb = findViewById(R.id.heroOrb); heroLogo = findViewById(R.id.heroLogo); heroMono = findViewById(R.id.heroMono)
        infoCard = findViewById(R.id.infoCard); infoLogo = findViewById(R.id.infoLogo); infoName = findViewById(R.id.infoName)
        infoCat = findViewById(R.id.infoCat); infoHintView = findViewById(R.id.infoHint); playerScrim = findViewById(R.id.playerScrim); playerClock = findViewById(R.id.playerClock)

        // glass surfaces
        findViewById<View>(R.id.segTrack).background = GlassDrawable(dp).apply { base = Color.argb(28, 255, 255, 255) }
        segPill.cornerRadius = 40 * dp; segIndicator.background = segPill
        heroOrb.background = orbGlass; heroOrb.outlineAmbientShadowColor = Accent.SIGNAL; heroOrb.outlineSpotShadowColor = Accent.SIGNAL
        heroOrb.elevation = 18 * dp
        infoCard.background = infoGlass
        findViewById<View>(R.id.infoLogoBox).background = GlassDrawable(dp, 16f)
        findViewById<TextView>(R.id.infoHint).text = SpannableStringBuilder().apply {
            key(this, "▲ ▼", "Change channel"); append("      "); key(this, "▶", "All channels"); append("      "); key(this, "Back", "Exit")
        }

        home = findViewById(R.id.home); homeFocusMode = home.descendantFocusability
        guide = findViewById(R.id.guide); guideScrim = findViewById(R.id.guideScrim); guideDivider = findViewById(R.id.guideDivider)
        catList = findViewById(R.id.catList); chList = findViewById(R.id.chList)
        chCol = findViewById(R.id.chCol); chHeader = findViewById(R.id.chHeader); channelSearch = findViewById(R.id.channelSearch)
        guide.background = GlassDrawable(dp, 28f).apply { base = Color.argb(185, 10, 12, 28) }
        guide.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        listOf(catList to catAdapter, chList to chAdapter).forEach { (rv, ad) ->
            rv.layoutManager = LinearLayoutManager(this); rv.adapter = ad; rv.itemAnimator = null
            rv.overScrollMode = View.OVER_SCROLL_NEVER
        }

        favs = prefs.getStringSet("favs", emptySet())!!.toSet()
        rowsView.layoutManager = SnapLayoutManager(this, RecyclerView.VERTICAL); rowsView.adapter = rowAdapter
        rowsView.itemAnimator = null
        rowsView.overScrollMode = View.OVER_SCROLL_NEVER
        rowsView.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ -> dimRows() }
        rowsView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) aurora.resume() else aurora.pause()
            }
        })
        channelSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim().orEmpty()
                guideChannels = if (q.isEmpty()) categoryChannels else categoryChannels.filter {
                    it.name.contains(q, ignoreCase = true) || it.category.contains(q, ignoreCase = true)
                }
                chHeader.text = "$guideCat  ${guideChannels.size}"
                chAdapter.notifyDataSetChanged()
                chList.scrollToPosition(0)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        listOf(segLive to 0, segSites to 1).forEach { (tv, i) ->
            tv.setOnClickListener { switchTab(i) }
            tv.setOnFocusChangeListener { _, f -> if (f) switchTab(i); styleSegs() }
        }
        segLive.post { moveIndicator(false) }
        styleSegs(); fillHero(null); loadChannels()

        val splash = findViewById<View>(R.id.splash); val logo = findViewById<View>(R.id.splashLogo)
        val glow = findViewById<View>(R.id.splashGlow)
        logo.scaleX = 0.85f; logo.scaleY = 0.85f; glow.alpha = 0f; glow.scaleX = 0.6f; glow.scaleY = 0.6f
        logo.animate().scaleX(1f).scaleY(1f).setDuration(720).setInterpolator(DecelerateInterpolator()).start()
        glow.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(1100).setInterpolator(DecelerateInterpolator(2f)).start()
        splash.postDelayed({
            splash.animate().alpha(0f).scaleX(1.06f).scaleY(1.06f).setDuration(500)
                .withEndAction { splash.visibility = View.GONE; rowsView.requestFocus() }.start()
        }, 1800)
    }

    // ---------- helpers ----------
    private fun key(sb: SpannableStringBuilder, k: String, label: String) {
        val s = sb.length; sb.append(k)
        sb.setSpan(ForegroundColorSpan(Accent.TEXT), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(TypefaceSpan(fSemi), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("  ").append(label)
    }
    private fun keyHint(k: String, label: String) = SpannableStringBuilder().also { key(it, k, label) }

    private fun chip(text: CharSequence) = TextView(this).apply {
        this.text = text; textSize = 13f; setTextColor(Accent.DIM); typeface = fRegular
        setPadding((14 * dp).toInt(), (7 * dp).toInt(), (14 * dp).toInt(), (7 * dp).toInt())
        background = GlassDrawable(dp)
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (10 * dp).toInt() }
    }

    private fun styleSegs() {
        val segFocused = segLive.isFocused || segSites.isFocused
        segPill.setColor(if (segFocused) Color.WHITE else Color.argb(40, 255, 255, 255))
        listOf(segLive to 0, segSites to 1).forEach { (tv, i) ->
            tv.setTextColor(when { tv.isFocused -> Accent.ABYSS; tab == i -> Accent.TEXT; else -> Accent.DIM })
        }
    }
    /** The selected-tab pill slides between tabs. */
    private fun moveIndicator(animate: Boolean) {
        val target = if (tab == 0) segLive else segSites
        if (target.width == 0) { target.post { moveIndicator(false) }; return }
        val lp = segIndicator.layoutParams
        val x0 = segIndicator.translationX; val w0 = lp.width
        val x1 = target.left.toFloat(); val w1 = target.width
        if (!animate || w0 <= 0) { segIndicator.translationX = x1; lp.width = w1; segIndicator.layoutParams = lp; return }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340; interpolator = OvershootInterpolator(0.9f)
            addUpdateListener {
                val f = it.animatedValue as Float
                segIndicator.translationX = x0 + (x1 - x0) * f
                lp.width = (w0 + (w1 - w0) * f).toInt(); segIndicator.layoutParams = lp
            }
            start()
        }
    }

    private fun focusAnim(x: View, f: Boolean, color: Int) {
        x.outlineAmbientShadowColor = color; x.outlineSpotShadowColor = color
        val s = if (f) 1.1f else 1f
        x.animate().setStartDelay(0).scaleX(s).scaleY(s).translationZ(if (f) 24 * dp else 0f)
            .setDuration(240).setInterpolator(DecelerateInterpolator(2f)).start()
    }
    /** Rows without focus step back so the active row reads first. */
    private fun dimRows() {
        val inRows = rowsView.hasFocus()
        for (i in 0 until rowsView.childCount) {
            val c = rowsView.getChildAt(i)
            val a = if (!inRows || c.hasFocus()) 1f else 0.45f
            if (c.alpha != a) c.animate().alpha(a).setDuration(220).start()
        }
    }
    private fun isInside(v: View, parent: View): Boolean {
        var p: ViewParent? = v.parent
        while (p != null) { if (p === parent) return true; p = p.parent }
        return false
    }
    private fun accentOf(id: String) = accents[id] ?: Accent.SIGNAL
    private fun extractAccent(id: String, d: android.graphics.drawable.Drawable, cb: (Int) -> Unit) {
        accents[id]?.let { cb(it); return }
        Accent.from(d) { c -> accents[id] = c; cb(c) }
    }

    private fun get(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 15000; c.setRequestProperty("User-Agent", UA)
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    // ---------- data ----------
    private fun loadChannels() {
        status.text = "Loading channels…"
        io.execute {
            try {
                val root = JSONObject(get(BASE))
                val arr = root.optJSONArray("channels") ?: root.optJSONArray("data") ?: root.optJSONArray("results")
                    ?: root.optJSONObject("data")?.optJSONArray("channels")
                    ?: throw IllegalStateException("The channel API returned no channel list")
                val list = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = firstString(o, "id", "channelId", "slug", "uuid")
                    val name = firstString(o, "name", "title", "channelName")
                    if (id.isBlank() || name.isBlank()) null else Channel(id, name,
                        firstString(o, "category", "group", "genre").ifBlank { "Other" },
                        firstString(o, "logo", "logoUrl", "image", "icon"))
                }
                if (list.isEmpty()) throw IllegalStateException("The channel API returned an empty list")
                runOnUiThread { all = list; status.text = ""; status.visibility = View.GONE; refreshRows(); rowsView.post { rowsView.requestFocus() } }
            } catch (e: Exception) { runOnUiThread { status.text = "Channels didn't load (${e.message}). Check the connection and reopen the app." } }
        }
    }

    private fun favTiles(): List<Tile> {
        val l = all.filter { it.id in favs }.map { Tile.Ch(it) }
        return if (l.isEmpty()) listOf(Tile.Hint) else l
    }

    private fun refreshRows() {
        rows = if (tab == 0) listOf(Row(FAV, favTiles())) +
                all.groupBy { it.category }.toSortedMap().map { (k, v) -> Row(k, v.map { Tile.Ch(it) }) }
               else listOf(Row("Movies & series", SITES.map { Tile.St(it) }))
        inner.clear()
        rowAdapter.notifyDataSetChanged()
        setHero(if (tab == 0) all.firstOrNull()?.let { Tile.Ch(it) } else Tile.St(SITES[0]), immediate = true)
    }

    private fun toggleFav(c: Channel) {
        val s = favs.toMutableSet(); val added = s.add(c.id); if (!added) s.remove(c.id)
        favs = s; prefs.edit().putStringSet("favs", s).apply()
        Toast.makeText(this, if (added) "Added to favorites" else "Removed from favorites", Toast.LENGTH_SHORT).show()
        for ((ri, ad) in inner) if (ri > 0) {
            val idx = rows[ri].tiles.indexOfFirst { it is Tile.Ch && it.c.id == c.id }
            if (idx >= 0) ad.notifyItemChanged(idx, "fav")
        }
        rows[0].tiles = favTiles(); rowAdapter.notifyItemChanged(0)
        (heroTile as? Tile.Ch)?.let { if (it.c.id == c.id) fillHero(it) }
    }

    private fun switchTab(t: Int) {
        if (t == tab) return
        val dir = if (t > tab) 1 else -1
        tab = t; styleSegs(); moveIndicator(true)
        val shift = 48 * dp * dir
        rowsView.animate().alpha(0f).translationX(-shift).setDuration(150).withEndAction {
            refreshRows(); rowsView.scrollToPosition(0)
            rowsView.translationX = shift
            rowsView.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(DecelerateInterpolator(1.5f)).start()
        }.start()
    }

    // ---------- hero ----------
    private val heroRunnable = Runnable { applyHero(heroTile) }
    private fun setHero(t: Tile?, immediate: Boolean = false) {
        heroTile = t; ui.removeCallbacks(heroRunnable)
        if (immediate) fillHero(t) else ui.postDelayed(heroRunnable, 130)   // debounce fast scrolling
    }
    private fun applyHero(t: Tile?) {
        heroText.animate().cancel()
        heroText.animate().alpha(0f).translationX(-10 * dp).setDuration(110).withEndAction {
            fillHero(t); heroText.translationX = 16 * dp
            heroText.animate().alpha(1f).translationX(0f).setDuration(300).setInterpolator(DecelerateInterpolator(2f)).start()
        }.start()
        heroOrb.scaleX = 0.94f; heroOrb.scaleY = 0.94f
        heroOrb.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(OvershootInterpolator(1.2f)).start()
    }
    private fun glowHero(c: Int, partner: Int) {
        aurora.setPalette(c, partner); orbGlass.accent = c
        heroOrb.outlineAmbientShadowColor = c; heroOrb.outlineSpotShadowColor = c
    }
    private fun fillHero(t: Tile?) {
        heroChips.removeAllViews()
        when (t) {
            is Tile.Ch -> {
                heroDot.visibility = View.VISIBLE; heroDot.backgroundTintList = ColorStateList.valueOf(Accent.LIVE)
                heroBadge.text = "Live now"; heroTitle.text = t.c.name
                heroChips.addView(chip(t.c.category))
                heroChips.addView(chip(keyHint("OK", "Watch")))
                heroChips.addView(chip(keyHint("Hold OK", if (t.c.id in favs) "Remove from favorites" else "Add to favorites")))
                heroMono.visibility = View.GONE; heroLogo.visibility = View.VISIBLE
                heroLogo.load(t.c.logo) {
                    allowHardware(false); crossfade(true)
                    listener(onSuccess = { _, r ->
                        extractAccent(t.c.id, r.drawable) { c -> if ((heroTile as? Tile.Ch)?.c?.id == t.c.id) glowHero(c, Accent.partner(c)) }
                    })
                }
                accents[t.c.id]?.let { glowHero(it, Accent.partner(it)) }
            }
            is Tile.St -> {
                heroDot.visibility = View.VISIBLE; heroDot.backgroundTintList = ColorStateList.valueOf(t.s.c1)
                heroBadge.text = "Movies & series"; heroTitle.text = t.s.name
                heroChips.addView(chip(Uri.parse(t.s.url).host ?: t.s.url))
                heroChips.addView(chip(keyHint("OK", "Open")))
                heroChips.addView(chip("Arrows move the pointer"))
                heroLogo.visibility = View.GONE; heroMono.visibility = View.VISIBLE; heroMono.text = t.s.name.take(1)
                glowHero(t.s.c1, t.s.c2)
            }
            else -> {
                heroDot.visibility = View.GONE; heroBadge.text = ""; heroTitle.text = "AK TV"
                heroMono.visibility = View.GONE; heroLogo.visibility = View.VISIBLE; heroLogo.setImageResource(R.drawable.ic_mark)
                glowHero(Accent.SIGNAL, Accent.INDIGO)
            }
        }
    }

    // ---------- player overlay ----------
    private val hideInfo = Runnable {
        infoCard.animate().alpha(0f).translationY(14 * dp).setDuration(450).start()
        playerScrim.animate().alpha(0f).setDuration(450).start()
        playerClock.animate().alpha(0f).setDuration(450).start()
    }
    private fun showInfo() {
        ui.removeCallbacks(hideInfo)
        infoCard.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).start()
        playerScrim.animate().alpha(1f).setDuration(260).start()
        playerClock.animate().alpha(1f).setDuration(260).start()
        ui.postDelayed(hideInfo, 4000)
    }
    private fun bindInfo(ch: Channel) {
        infoName.text = ch.name
        infoCat.text = "${ch.category}   ${current + 1} of ${shown.size}"
        infoHintView.text = "Loading programme information…"
        infoLogo.load(ch.logo)
        infoGlass.accent = accentOf(ch.id)
        showInfo()
        // Public guide API: schedule data is independent of stream/DRM data.
        // Fetch on channel change and refresh while this channel remains selected.
        guideRequestChannel = ch.id
        io.execute {
            try {
                val g = JSONObject(get("https://livetgtv.lovable.app/api/public/guide/${Uri.encode(ch.id)}"))
                val now = g.optJSONObject("nowPlaying")
                val next = g.optJSONObject("upNext")
                val nowTitle = now?.optString("title", "")?.takeIf { it.isNotBlank() && it != "null" }
                val nextTitle = next?.optString("title", "")?.takeIf { it.isNotBlank() && it != "null" }
                val nowTime = now?.optString("startTime", "")?.takeIf { it.isNotBlank() && it != "null" }
                val nextTime = next?.optString("startTime", "")?.takeIf { it.isNotBlank() && it != "null" }
                runOnUiThread {
                    if (guideRequestChannel != ch.id || activeChannelId != ch.id || isFinishing) return@runOnUiThread
                    infoCat.text = if (nowTitle != null) "NOW  $nowTitle${if (nowTime != null) " · $nowTime" else ""}" else "${ch.category}   ${current + 1} of ${shown.size}"
                    infoHintView.text = if (nextTitle != null) "UP NEXT  $nextTitle${if (nextTime != null) " · $nextTime" else ""}" else "Programme information unavailable"
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (guideRequestChannel == ch.id && activeChannelId == ch.id && !isFinishing) {
                        infoCat.text = "${ch.category}   ${current + 1} of ${shown.size}"
                        infoHintView.text = "Programme information unavailable"
                    }
                }
            }
        }
    }

    // ---------- playback: resolve API sources, then use Media3 auto-detection ----------
    private fun firstString(o: JSONObject, vararg keys: String): String {
        for (k in keys) { val v = o.opt(k); if (v is String && v.isNotBlank() && v != "null") return v }
        return ""
    }
    private fun resolveChannel(id: String): JSONObject {
        var last: Exception? = null
        try { return JSONObject(get("$BASE/${Uri.encode(id)}")) } catch (e: Exception) { last = e }
        throw last ?: IllegalStateException("Channel details unavailable")
    }
    private fun collectUrls(value: Any?, out: MutableList<String>) {
        when (value) {
            is String -> if ((value.startsWith("http://") || value.startsWith("https://")) &&
                (value.contains(".m3u8") || value.contains(".mpd") || value.contains(".mp4") || value.contains(".m4v") || value.contains(".ts") || value.contains("/stream") || value.contains("/play"))) out.add(value)
            is JSONObject -> {
                for (key in listOf("url", "src", "streamUrl", "stream_url", "playbackUrl", "playUrl", "file", "manifest", "hls", "dash", "source"))
                    collectUrls(value.opt(key), out)
                val keys = value.keys(); while (keys.hasNext()) { val k = keys.next(); if (k !in listOf("url", "src", "streamUrl", "stream_url", "playbackUrl", "playUrl", "file", "manifest", "hls", "dash", "source")) {
                    val v = value.opt(k); if (v is JSONObject || v is org.json.JSONArray) collectUrls(v, out)
                } }
            }
            is org.json.JSONArray -> for (i in 0 until value.length()) collectUrls(value.opt(i), out)
        }
    }
    private fun play(index: Int) {
        if (shown.isEmpty()) return
        // Use the website's current per-channel embed endpoint for every channel.
        // The website provides the player and server selection inside /embed/{channelId}.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        current = (index + shown.size) % shown.size
        val ch = shown[current]
        activeChannelId = ch.id
        activeSources = emptyList()
        activeEmbed = "https://livetgtv.lovable.app/embed/${Uri.encode(ch.id)}"
        sourceIndex = 0
        bindInfo(ch)
        spinner.visibility = View.VISIBLE
        releasePlayer()

        // Resolve fresh signed URLs immediately before playback; tokens expire.
        // Native Media3 playback supports DASH + ClearKey, with the website embed as fallback.
        io.execute {
            try {
                val details = resolveChannel(ch.id)
                val sourceArray = details.optJSONArray("sources")
                val sources = if (sourceArray != null) (0 until sourceArray.length())
                    .mapNotNull { sourceArray.optString(it).takeIf { u -> u.startsWith("https://") || u.startsWith("http://") } }
                    else emptyList()
                val drm = details.optJSONObject("drm")
                runOnUiThread {
                    if (activeChannelId != ch.id || isFinishing) return@runOnUiThread
                    activeSources = sources
                    sourceIndex = 0
                    if (sources.isNotEmpty()) startSource(0, drm)
                    else {
                        spinner.visibility = View.GONE
                        startActivity(Intent(this, BrowserActivity::class.java).putExtra("url", activeEmbed))
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (activeChannelId == ch.id && !isFinishing) {
                        activeSources = emptyList()
                        spinner.visibility = View.GONE
                        startActivity(Intent(this, BrowserActivity::class.java).putExtra("url", activeEmbed))
                    }
                }
            }
        }
    }

    private fun hexToB64Url(hex: String): String {
        val clean = hex.trim().removePrefix("0x").replace(" ", "")
        require(clean.isNotEmpty() && clean.length % 2 == 0 && clean.matches(Regex("[0-9a-fA-F]+"))) { "Invalid ClearKey hex value" }
        val bytes = ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun startSource(index: Int, drm: JSONObject?) {
        if (index !in activeSources.indices) {
            spinner.visibility = View.GONE
            val embed = activeEmbed
            if (!embed.isNullOrBlank() && embed.startsWith("http")) startActivity(Intent(this, BrowserActivity::class.java).putExtra("url", embed))
            else Toast.makeText(this, "All available stream servers failed. Try another server or channel.", Toast.LENGTH_LONG).show()
            return
        }
        sourceIndex = index
        val url = activeSources[index]
        val token = Regex("__hdnea__=([^&]+)").find(url)?.groupValues?.get(1)
        val hdrs = HashMap<String, String>(); if (token != null) hdrs["Cookie"] = "__hdnea__=$token"
        val http = DefaultHttpDataSource.Factory().setUserAgent(UA)
            .setDefaultRequestProperties(hdrs).setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000).setReadTimeoutMs(20000)
        val mediaFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(http)
        if (drm != null && drm.has("keyId") && drm.has("key")) {
            try {
                val lic = """{"keys":[{"kty":"oct","k":"${hexToB64Url(drm.getString("key"))}","kid":"${hexToB64Url(drm.getString("keyId"))}"}],"type":"temporary"}"""
                val mgr = DefaultDrmSessionManager.Builder().setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .setMultiSession(true).build(LocalMediaDrmCallback(lic.toByteArray()))
                mediaFactory.setDrmSessionManagerProvider { mgr }
            } catch (_: Exception) { /* Continue without malformed optional DRM metadata; player will report the actual stream error. */ }
        }
        val item = MediaItem.Builder().setUri(url)
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(8000).build()).build()
        val p = ExoPlayer.Builder(this).build()
        p.setMediaSource(mediaFactory.createMediaSource(item)); p.playWhenReady = true
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                if (s == Player.STATE_READY) { spinner.visibility = View.GONE; retries = 0 }
                if (s == Player.STATE_BUFFERING) spinner.visibility = View.VISIBLE
            }
            override fun onPlayerError(e: PlaybackException) {
                p.release(); if (player === p) { player = null; pv.player = null }
                if (activeChannelId == shown.getOrNull(current)?.id && index + 1 < activeSources.size) startSource(index + 1, drm)
                else {
                    spinner.visibility = View.GONE
                    val c = e.cause
                    val extra = if (c is HttpDataSource.InvalidResponseCodeException) " HTTP ${c.responseCode}" else ""
                    val embed = activeEmbed
                    if (!embed.isNullOrBlank() && embed.startsWith("http")) startActivity(Intent(this@MainActivity, BrowserActivity::class.java).putExtra("url", embed))
                    else Toast.makeText(this@MainActivity, "Playback error: ${e.errorCodeName}$extra", Toast.LENGTH_LONG).show()
                }
            }
        })
        pv.player = p; player = p; p.prepare(); showInfo()
    }
    private fun releasePlayer() { pv.player = null; player?.release(); player = null }
    private fun closePlayer() {
        releasePlayer()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        retries = 0; ui.removeCallbacks(hideInfo); aurora.resume(); resetGuide()
        if (layer.visibility == View.VISIBLE) layer.animate().alpha(0f).setDuration(200).withEndAction { layer.visibility = View.GONE }.start()
    }
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (layer.visibility == View.VISIBLE && guideShown) return guideKey(e) || super.dispatchKeyEvent(e)
        if (layer.visibility == View.VISIBLE && e.action == KeyEvent.ACTION_DOWN) when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> { closePlayer(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { openGuide(); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { retries = 0; play(current - 1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { retries = 0; play(current + 1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { showInfo(); return true }
        }
        // Going up out of the rows always lands on the current tab, never switches tabs by accident.
        if (layer.visibility != View.VISIBLE && e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            val f = currentFocus
            if (f != null && isInside(f, rowsView)) {
                val next = FocusFinder.getInstance().findNextFocus(window.decorView as ViewGroup, f, View.FOCUS_UP)
                if (next === segLive || next === segSites) { (if (tab == 0) segLive else segSites).requestFocus(); return true }
            }
        }
        return super.dispatchKeyEvent(e)
    }
    override fun onStart() { super.onStart(); if (layer.visibility != View.VISIBLE) aurora.resume() }
    override fun onStop() {
        super.onStop()
        releasePlayer()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        layer.visibility = View.GONE; aurora.pause(); resetGuide()
    }

    // ---------- channel guide (Right opens · Left goes back · OK plays) ----------
    private fun channelsIn(cat: String) = if (cat == FAV) all.filter { it.id in favs } else all.filter { it.category == cat }
    private fun within(v: View?, rv: View) = v != null && (v === rv || isInside(v, rv))
    private fun itemOf(v: View, rv: RecyclerView): View { var x = v; while (x.parent !== rv) x = x.parent as View; return x }
    private val guideTimeout = Runnable { closeGuide() }
    private fun bumpGuide() { ui.removeCallbacks(guideTimeout); ui.postDelayed(guideTimeout, 15000) }

    private fun focusRow(rv: RecyclerView, i: Int) {
        val h = rv.findViewHolderForAdapterPosition(i)
        if (h != null && h.itemView.isShown) { h.itemView.requestFocus(); return }
        (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, (120 * dp).toInt())
        rv.post { rv.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() ?: rv.requestFocus() }
    }

    private fun openGuide() {
        if (all.isEmpty()) return
        val byCat = all.groupBy { it.category }
        val favCount = all.count { it.id in favs }
        guideCats = (if (favCount > 0) listOf(FAV) else emptyList()) + byCat.keys.sorted()
        catCounts = byCat.mapValues { it.value.size } + (FAV to favCount)
        if (home.descendantFocusability != ViewGroup.FOCUS_BLOCK_DESCENDANTS) {
            lastHomeFocus = currentFocus; home.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        guideShown = true; guideCat = ""
        guide.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        chCol.visibility = View.GONE; guideDivider.visibility = View.GONE
        channelSearch.setText("")
        catAdapter.notifyDataSetChanged()
        ui.removeCallbacks(hideInfo); hideInfo.run()
        guide.animate().cancel(); guideScrim.animate().cancel()
        guide.visibility = View.VISIBLE; guide.alpha = 0f; guide.translationX = 60 * dp
        guide.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).start()
        guideScrim.visibility = View.VISIBLE; guideScrim.alpha = 0f
        guideScrim.animate().alpha(1f).setDuration(260).start()
        focusRow(catList, guideCats.indexOf(shownCat).coerceAtLeast(0))
        bumpGuide()
    }

    private fun openCategory(i: Int) {
        val cat = guideCats.getOrNull(i) ?: return
        val prev = guideCats.indexOf(guideCat)
        guideCat = cat; categoryChannels = channelsIn(cat)
        channelSearch.setText("")
        guideChannels = categoryChannels
        chHeader.text = "$cat  ${guideChannels.size}"
        chAdapter.notifyDataSetChanged()
        if (chCol.visibility != View.VISIBLE) {
            chCol.animate().cancel()
            chCol.visibility = View.VISIBLE; guideDivider.visibility = View.VISIBLE
            chCol.alpha = 0f; chCol.translationX = 30 * dp
            chCol.animate().alpha(1f).translationX(0f).setDuration(240).setInterpolator(DecelerateInterpolator(2f)).start()
        }
        val playing = if (cat == shownCat) guideChannels.indexOfFirst { it.id == shown.getOrNull(current)?.id } else -1
        chList.scrollToPosition(0)
        focusRow(chList, playing.coerceAtLeast(0))
        if (prev >= 0) catAdapter.notifyItemChanged(prev)
        catAdapter.notifyItemChanged(i)
    }

    private fun backToCats() {
        val i = guideCats.indexOf(guideCat).coerceAtLeast(0)
        guideCat = ""
        focusRow(catList, i)
        catAdapter.notifyItemChanged(i)
        chCol.animate().cancel()
        chCol.animate().alpha(0f).translationX(30 * dp).setDuration(180)
            .withEndAction { if (guideCat.isEmpty()) { chCol.visibility = View.GONE; guideDivider.visibility = View.GONE } }.start()
    }

    private fun closeGuide() {
        if (!guideShown) return
        guideShown = false; guideCat = ""; ui.removeCallbacks(guideTimeout)
        guide.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        currentFocus?.clearFocus()
        guide.animate().alpha(0f).translationX(60 * dp).setDuration(200)
            .withEndAction { if (!guideShown) { guide.visibility = View.GONE; chCol.visibility = View.GONE; guideDivider.visibility = View.GONE } }.start()
        guideScrim.animate().alpha(0f).setDuration(200).withEndAction { if (!guideShown) guideScrim.visibility = View.GONE }.start()
    }

    /** Hard reset when the player itself closes: hide the guide and give focus back to the home screen. */
    private fun resetGuide() {
        guideShown = false; guideCat = ""; ui.removeCallbacks(guideTimeout)
        guide.animate().cancel(); guideScrim.animate().cancel()
        guide.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        guide.visibility = View.GONE; guideScrim.visibility = View.GONE; chCol.visibility = View.GONE; guideDivider.visibility = View.GONE
        if (home.descendantFocusability == ViewGroup.FOCUS_BLOCK_DESCENDANTS) {
            home.descendantFocusability = homeFocusMode
            val back = lastHomeFocus; lastHomeFocus = null
            if (back?.takeIf { it.isAttachedToWindow }?.requestFocus() != true) rowsView.requestFocus()
        }
    }

    private fun playFromGuide(i: Int) {
        val list = guideChannels; val cat = guideCat
        if (i !in list.indices) return
        shown = list; shownCat = cat; retries = 0
        closeGuide(); play(i)
    }

    private fun guideKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return false
        bumpGuide()
        val f = currentFocus
        val inCh = within(f, chList); val inCat = within(f, catList)
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> { closeGuide(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (inCh) backToCats() else closeGuide(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (inCat && f != null && f !== catList) openCategory(catList.getChildAdapterPosition(itemOf(f, catList)))
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!inCh && !inCat) { focusRow(catList, 0); return true }
                val list = if (inCh) chList else catList
                // stay inside the current column; never jump across or out of the panel
                val next = f!!.focusSearch(if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP) View.FOCUS_UP else View.FOCUS_DOWN)
                return next == null || !within(next, list)
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> return true
        }
        return false   // OK / long-press go to the focused row as normal
    }

    private val catAdapter = object : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = guideCats.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val v = LayoutInflater.from(p.context).inflate(R.layout.item_guide_cat, p, false)
            v.background = GlassDrawable(dp, 14f).apply { idleVisible = false }
            return VH(v)
        }
        override fun onBindViewHolder(h: VH, i: Int) {
            val v = h.itemView; val cat = guideCats[i]; val glass = v.background as GlassDrawable
            val name = v.findViewById<TextView>(R.id.catName)
            name.text = cat
            v.findViewById<TextView>(R.id.catCount).text = (catCounts[cat] ?: 0).toString()
            v.findViewById<View>(R.id.catMark).visibility = if (cat == shownCat) View.VISIBLE else View.INVISIBLE
            val open = cat == guideCat
            val style = { f: Boolean ->
                glass.focused = f; glass.idleVisible = open
                name.setTextColor(if (f || open) Accent.TEXT else Accent.DIM)
            }
            style(v.isFocused)
            v.setOnFocusChangeListener { _, f -> style(f) }
            v.setOnClickListener { openCategory(i) }
        }
    }

    private val chAdapter = object : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = guideChannels.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val v = LayoutInflater.from(p.context).inflate(R.layout.item_guide_ch, p, false)
            v.background = GlassDrawable(dp, 14f).apply { idleVisible = false }
            v.findViewById<View>(R.id.chLogoBox).background = GlassDrawable(dp, 12f)
            return VH(v)
        }
        override fun onBindViewHolder(h: VH, i: Int) {
            val v = h.itemView; val ch = guideChannels[i]; val glass = v.background as GlassDrawable
            val name = v.findViewById<TextView>(R.id.chName)
            name.text = ch.name
            v.findViewById<TextView>(R.id.chNum).text = (i + 1).toString()
            v.findViewById<ImageView>(R.id.chLogo).load(ch.logo)
            val playing = shown.getOrNull(current)?.id == ch.id
            v.findViewById<View>(R.id.chNow).visibility = if (playing) View.VISIBLE else View.GONE
            v.findViewById<View>(R.id.chFav).visibility = if (ch.id in favs) View.VISIBLE else View.GONE
            glass.accent = accentOf(ch.id)
            val style = { f: Boolean ->
                glass.focused = f; glass.idleVisible = playing
                name.setTextColor(if (f || playing) Accent.TEXT else Accent.DIM)
            }
            style(v.isFocused)
            v.setOnFocusChangeListener { _, f -> style(f) }
            v.setOnClickListener { playFromGuide(i) }
            v.setOnLongClickListener { toggleFav(ch); notifyItemChanged(i); true }
        }
    }

    // ---------- adapters ----------
    private inner class TileAdapter(val row: Row) : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = row.tiles.size
        override fun getItemViewType(i: Int) = when (row.tiles[i]) { is Tile.Ch -> 0; is Tile.St -> 1; else -> 2 }
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val l = when (t) { 0 -> R.layout.item_tile; 1 -> R.layout.item_site_tile; else -> R.layout.item_hint }
            val v = LayoutInflater.from(p.context).inflate(l, p, false)
            if (t != 1) v.background = GlassDrawable(dp, 20f)
            return VH(v)
        }
        override fun onBindViewHolder(h: VH, i: Int, payloads: MutableList<Any>) {
            val t = row.tiles[i]
            if (payloads.isNotEmpty() && t is Tile.Ch) { h.itemView.findViewById<View>(R.id.fav).visibility = if (t.c.id in favs) View.VISIBLE else View.GONE; return }
            super.onBindViewHolder(h, i, payloads)
        }
        override fun onBindViewHolder(h: VH, i: Int) {
            val v = h.itemView
            when (val t = row.tiles[i]) {
                is Tile.Ch -> {
                    val glass = v.background as GlassDrawable
                    v.tag = t.c.id; glass.accent = accentOf(t.c.id); glass.focused = v.isFocused
                    v.findViewById<TextView>(R.id.name).text = t.c.name
                    v.findViewById<ImageView>(R.id.logo).load(t.c.logo) {
                        allowHardware(false)
                        listener(onSuccess = { _, r ->
                            extractAccent(t.c.id, r.drawable) { c ->
                                if (v.tag == t.c.id) { glass.accent = c; if (v.isFocused) focusAnim(v, true, c) }
                            }
                        })
                    }
                    v.findViewById<View>(R.id.fav).visibility = if (t.c.id in favs) View.VISIBLE else View.GONE
                    v.setOnFocusChangeListener { x, f ->
                        val c = accentOf(t.c.id); glass.accent = c; glass.focused = f
                        focusAnim(x, f, c); if (f) setHero(t)
                    }
                    v.setOnClickListener {
                        shown = row.tiles.filterIsInstance<Tile.Ch>().map { it.c }; shownCat = row.title
                        retries = 0; play(shown.indexOfFirst { it.id == t.c.id })
                    }
                    v.setOnLongClickListener { toggleFav(t.c); true }
                }
                is Tile.St -> {
                    val grad = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(t.s.c1, t.s.c2)).apply { cornerRadius = 24 * dp }
                    val glass = GlassDrawable(dp, 24f).apply { accent = t.s.c1; showFill = false }
                    v.background = LayerDrawable(arrayOf(grad, glass)); v.clipToOutline = true
                    v.findViewById<TextView>(R.id.siteMono).text = t.s.name
                    v.findViewById<TextView>(R.id.siteName).text = t.s.name
                    v.findViewById<TextView>(R.id.siteUrl).text = t.s.url.removePrefix("https://")
                    v.setOnFocusChangeListener { x, f -> glass.focused = f; focusAnim(x, f, t.s.c1); if (f) setHero(t) }
                    v.setOnClickListener { startActivity(Intent(this@MainActivity, BrowserActivity::class.java).putExtra("url", t.s.url)) }
                }
                else -> {}
            }
        }
    }

    private val rowAdapter = object : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_row, p, false))
        override fun onBindViewHolder(h: VH, i: Int) {
            val v = h.itemView
            val row = rows[i]
            v.findViewById<TextView>(R.id.rowTitle).text = row.title
            val n = row.tiles.count { it is Tile.Ch }
            v.findViewById<TextView>(R.id.rowCount).text = if (n > 0) n.toString() else ""
            val rv = v.findViewById<RecyclerView>(R.id.rowList)
            if (rv.layoutManager == null) {
                rv.layoutManager = SnapLayoutManager(this@MainActivity, RecyclerView.HORIZONTAL)
                rv.itemAnimator = null
                rv.overScrollMode = View.OVER_SCROLL_NEVER
                rv.setHasFixedSize(true)
                rv.isNestedScrollingEnabled = false
            }
            val ad = TileAdapter(row); inner[i] = ad; rv.adapter = ad
            v.animate().cancel(); v.translationY = 0f
            v.alpha = if (rowsView.hasFocus()) 0.45f else 1f
        }
    }
}
