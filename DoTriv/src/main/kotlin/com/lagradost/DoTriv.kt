package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class DoTriv : MainAPI() {

    override var mainUrl   = "https://dospiv.com" 
    override var name      = "DoTriv"
    override var lang      = "fr"
    override val hasMainPage        = true
    override val hasDownloadSupport = true
    override val supportedTypes     = setOf(TvType.Movie)

    // Variables dynamiques qui s'adaptent aux changements du site
    private var dynamicFolder   = "fed960f" 
    private var dynamicProvider = "dotriv"   
    private var isInitialized   = false

    private val cfKiller by lazy { CloudflareKiller() }

    private suspend fun getDoc(url: String): Document =
        app.get(url, interceptor = cfKiller).document

    private suspend fun postDoc(url: String, data: Map<String, String>): Document =
        app.post(url, data = data, interceptor = cfKiller).document

    // Récupère automatiquement le 'folder' et 'provider' sur la page d'accueil
    private suspend fun initDynamicVariables() {
        if (isInitialized) return
        try {
            val homeDoc = getDoc(mainUrl)
            val entryButton = homeDoc.selectFirst("a[aria-label*=Entrer], a[href^=fed], #recptionbc42c") 
                ?: homeDoc.selectFirst("body > a")

            entryButton?.let { el ->
                val href = el.attr("href").trim('/')
                if (href.isNotBlank() && !href.startsWith("http")) {
                    dynamicFolder = href
                }
                
                val label = el.attr("aria-label").lowercase()
                if (label.contains("sur ")) {
                    dynamicProvider = label.substringAfter("sur ").trim()
                }
            }
            isInitialized = true
        } catch (e: Exception) {
            // En cas d'échec, conserve les valeurs par défaut
        }
    }

    @Serializable
    data class LoadLinkData(
        val playerUrl : String,
        val poster    : String? = null,
        val isVostfr  : Boolean = false,
    )

    companion object {
        private val CATEGORIES = listOf(
            "Derniers ajouts"   to "home",
            "À l'affiche"       to "29",
            "Action"            to "1",
            "Animation"         to "2",
            "Aventure"          to "4",
            "Comédie"           to "6",
            "Drame"             to "7",
            "Fantastique"       to "8",
            "Horreur"           to "9",
            "Policier"          to "10",
            "Science-Fiction"   to "11",
            "Thriller"          to "12",
            "Documentaire"      to "26",
            "Spectacle"         to "3",
        )
    }

    override val mainPage: List<MainPageData>
        get() = CATEGORIES.map { (label, id) ->
            MainPageData(label, "$mainUrl/TOKEN_FOLDER/c/TOKEN_PROVIDER/$id")
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        initDynamicVariables()
        
        val items = mutableListOf<SearchResponse>()
        val isHome = request.data.contains("/home/")

        val targetUrl = if (isHome) {
            val offset = (page - 1) * 20
            "$mainUrl/$dynamicFolder/api_films.php?offset=$offset&limit=20&folder=$dynamicFolder&pr=$dynamicProvider"
        } else {
            val cleanId = request.data.substringAfterLast("/")
            if (request.data.contains("/home")) {
                "$mainUrl/$dynamicFolder/home/$dynamicProvider"
            } else {
                "$mainUrl/$dynamicFolder/c/$dynamicProvider/$cleanId/${page - 1}"
            }
        }

        try {
            if (isHome) {
                val res = app.get(targetUrl, interceptor = cfKiller).text
                AppUtils.tryParseJson<ApiFilmsResponse>(res)?.films?.forEach { 
                    it.toSearchResult()?.let { searchRes -> items.add(searchRes) }
                }
            } else {
                val doc = getDoc(targetUrl)
                // Cible à la fois les structures classiques et les 'trend-card'
                doc.select("a.film-card, a.showcase-card, a.trend-card").forEach {
                    it.toSearchResult()?.let { searchRes -> items.add(searchRes) }
                }
            }
        } catch (e: Exception) { }

        return newHomePageResponse(request.name, items.toList(), hasNext = items.size >= 20)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        initDynamicVariables()
        val doc = postDoc(
            "$mainUrl/$dynamicFolder/home/$dynamicProvider",
            mapOf("searchword" to query)
        )
        return doc.select("a.film-card, a.trend-card, a.showcase-card")
            .mapNotNull { it.toSearchResult() }
    }

    // Version corrigée pour extraire les "trend-card" qui restaient vides
    private fun Element.toSearchResult(): SearchResponse? {
        val href  = absUrl("href").ifEmpty { return null }
        val img   = selectFirst(".trend-card-img, img")
        
        val title = img?.attr("alt")?.trim()?.ifEmpty { null }
            ?: selectFirst(".trend-card-title, .film-card-title")?.text()?.trim()
            ?: return null
        
        val poster = img?.attr("data-src")?.ifEmpty { null } 
            ?: img?.attr("src")?.ifEmpty { null }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster?.let { fixUrl(it) }
        }
    }

    private fun ApiFilm.toSearchResult(): SearchResponse? {
        if (link.isEmpty()) return null
        val cleanTitle = title.replace(Regex("""\s*\(\d{4}\)"""), "").trim()
        return newMovieSearchResponse(cleanTitle, link, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        initDynamicVariables()
        val doc = getDoc(url)

        val title = doc.selectFirst(".film-detail-title, h1")?.text()?.trim() ?: return null
        val poster = doc.selectFirst(".film-detail-poster img, .film-player-poster img")?.absUrl("src")
        val synopsis = doc.selectFirst(".film-synopsis-text, .film-detail-synopsis")?.text()?.trim()
        val year = doc.selectFirst(".trend-card-date, .film-detail-badges .film-detail-badge")
            ?.text()?.trim()?.toIntOrNull()
        val genre = doc.selectFirst(".film-detail-cat")?.text()?.trim()

        val playerUrl = doc.selectFirst(".film-player iframe, #player iframe, iframe[src]")
            ?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
            ?: ""

        // Correction Kotlinx standard
        val dataJson = Json.encodeToString(LoadLinkData(playerUrl = playerUrl, poster = poster))

        return newMovieLoadResponse(title, url, TvType.Movie, dataJson) {
            this.posterUrl = poster
            this.plot      = synopsis
            this.year      = year
            this.tags      = listOfNotNull(genre)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val linkData = AppUtils.tryParseJson<LoadLinkData>(data) ?: return false
        val playerUrl = fixUrl(linkData.playerUrl)

        if (playerUrl.isBlank()) return false

        loadExtractor(playerUrl, mainUrl, subtitleCallback, callback)

        if (playerUrl.contains(mainUrl) || playerUrl.contains("dospiv") || playerUrl.contains(dynamicProvider)) {
            val doc = getDoc(playerUrl)
            
            doc.select("iframe, source").forEach { el ->
                val src = el.attr("src").ifEmpty { el.attr("data-src") }
                if (src.isNotBlank()) {
                    loadExtractor(fixUrl(src), mainUrl, subtitleCallback, callback)
                }
            }

            doc.select("script").forEach { script ->
                val text = script.data()
                
                Regex("""["'](https?://[^"']+\.m3u8[^"']*|https?://[^"']+\.mp4[^"']*)["']""")
                    .findAll(text).forEach { mr ->
                        val src = mr.groupValues[1]
                        val isM3u = src.contains("m3u8")
                        
                        // Correction de la signature du referer/headers
                        callback(
                            newExtractorLink(
                                source = this.name,
                                name = this.name,
                                url = src,
                                type = if (isM3u) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.quality = if (isM3u) Qualities.Unknown.value else Qualities.P1080.value
                                this.referer = playerUrl
                                this.headers = mapOf(
                                    "Accept" to "*/*",
                                    "Referer" to playerUrl
                                )
                            }
                        )
                    }
            }
        }
        return true
    }

    @Serializable
    data class ApiFilmsResponse(
        val films   : List<ApiFilm> = emptyList(),
        val hasMore : Boolean       = false,
    )

    @Serializable
    data class ApiFilm(
        val id     : Int    = 0,
        val title  : String = "",
        val link   : String = "",
        val poster : String = "",
        val vostfr : Boolean = false,
    )
}