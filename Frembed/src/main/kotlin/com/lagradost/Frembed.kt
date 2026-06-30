package com.lagradost

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLinkType

class Frembed : MainAPI() {
    override var mainUrl = "https://frembed.skin"
    override var name = "05- 🍿⭐ Frembed FR 🎬"
    override val hasQuickSearch = true
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val cfKiller = CloudflareKiller()
    private val tmdbApiKey = "5a41c74aa2eda5f25a09d3d4e47d46b7"
    private val tmdbBase = "https://api.themoviedb.org/3"
    private val imageBase = "https://image.tmdb.org/t/p/w500"
    private val TAG = "Frembed"
    private var isInitialized = false

    // ══ DATA CLASSES ══════════════════════════════════════════
    data class WfUrlData(
        @JsonProperty("title") val title: String,
        @JsonProperty("url") val url: String
    )

    data class TmdbSearchResult(@JsonProperty("results") val results: List<TmdbItem> = emptyList())
    
    data class TmdbItem(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("media_type") val mediaType: String? = null
    )

    data class TmdbMovieDetail(
        @JsonProperty("title") val title: String,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null
    )

    data class TmdbSerieDetail(
        @JsonProperty("name") val name: String,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("seasons") val seasons: List<TmdbSeason>? = null
    )

    data class TmdbSeason(@JsonProperty("season_number") val seasonNumber: Int)
    data class TmdbSeasonDetail(@JsonProperty("episodes") val episodes: List<TmdbEpisode> = emptyList())
    
    data class TmdbEpisode(
        @JsonProperty("episode_number") val episodeNumber: Int,
        @JsonProperty("name") val name: String? = null
    )

    data class VideoLinkData(
        @JsonProperty("tmdbId") val tmdbId: Int,
        @JsonProperty("type") val type: String,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null
    )

    // ══ INITIALISATION DYNAMIQUE ══════════════════════════════
    private suspend fun initMainUrl() {
        if (isInitialized) return
        try {
            val json = app.get("https://amsc.duckdns.org/cloudstream/repo/urls.json").text
            val urlData = tryParseJson<List<WfUrlData>>(json) ?: emptyList()
            val urlFromJson = urlData.find { it.title.contains("Frem", ignoreCase = true) }?.url?.removeSuffix("/")
            if (!urlFromJson.isNullOrBlank()) {
                mainUrl = urlFromJson
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur initMainUrl: ${e.message}")
        }
        isInitialized = true
    }

    private suspend fun getPosterFromTmdb(tmdbId: String, isMovie: Boolean): String? {
        return try {
            val typePath = if (isMovie) "movie" else "tv"
            val res = app.get("$tmdbBase/$typePath/$tmdbId?api_key=$tmdbApiKey").text
            val json = parseJson<Map<String, Any>>(res)
            val path = json["poster_path"]?.toString()
            if (path != null) "$imageBase$path" else null
        } catch (e: Exception) { null }
    }

    // ══ CONFIGURATION PAGE D'ACCUEIL ══════════════════════════
    override val mainPage = mainPageOf(
        "movie/popular"                     to "Films Populaires 🎬",
        "tv/popular"                        to "Séries Populaires 📺",
        "movie/top_rated"                   to "Top 250 Films ⭐",
        "tv/top_rated"                      to "Top 250 Séries ⭐",
        "discover/movie?with_genres=28"     to "Films Action 💥",
        "discover/movie?with_genres=12"     to "Films Aventure 🗺️",
        "discover/movie?with_genres=16"     to "Films Animation 🎨",
        "discover/movie?with_genres=35"     to "Films Comédie 😂",
        "discover/movie?with_genres=27"     to "Films Horreur 👻",
        "discover/movie?with_genres=878"    to "Films Science-Fiction 🚀",
        "discover/tv?with_genres=10759"     to "Séries Action & Aventure 🔫",
        "discover/tv?with_genres=16"        to "Séries Animation 🖌️",
        "discover/tv?with_genres=10765"     to "Séries Sci-Fi & Fantasy 👽",
        "anime"                             to "Animes ⛩️"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        initMainUrl()
        
        return if (request.data == "anime") {
            val res = app.get("$mainUrl/api/public/v1/anime?page=$page", interceptor = cfKiller).text
            val data = parseJson<Map<String, Any>>(res)
            val results = (data["result"] as? Map<*, *>)?.get("items") as? List<Map<*, *>>
            
            val items = results?.mapNotNull { item ->
                val title = item["title"]?.toString() ?: return@mapNotNull null
                val id    = item["tmdb"]?.toString() ?: return@mapNotNull null
                val type  = item["type"]?.toString() ?: "tv"
                val rawPoster = item["poster"]?.toString()

                val finalPosterUrl = if (!rawPoster.isNullOrBlank()) {
                    if (rawPoster.startsWith("http")) rawPoster else "$imageBase$rawPoster"
                } else {
                    getPosterFromTmdb(id, type == "movie")
                }

                newAnimeSearchResponse(title, "$mainUrl/anime-$type/$id", TvType.Anime) {
                    this.posterUrl = finalPosterUrl
                }
            } ?: emptyList()
            newHomePageResponse(request.name, items)
        } else {
            val separator = if (request.data.contains("?")) "&" else "?"
            val url = "$tmdbBase/${request.data}${separator}api_key=$tmdbApiKey&language=fr-FR&page=$page"
            val data = parseJson<TmdbSearchResult>(app.get(url).text)
            val items = data.results.mapNotNull { item ->
                val isMovie = item.title != null
                val cleanName = (item.title ?: item.name ?: return@mapNotNull null)
                    .lowercase()
                    .replace(Regex("[^a-z0-9\\s]"), "")
                    .replace(" ", "-")

                newMovieSearchResponse(
                    item.title ?: item.name ?: return@mapNotNull null,
                    "$mainUrl/${if (isMovie) "movies" else "tv-show"}/$cleanName/${item.id}",
                    if (isMovie) TvType.Movie else TvType.TvSeries
                ) {
                    this.posterUrl = item.posterPath?.let { "$imageBase$it" }
                }
            }
            newHomePageResponse(request.name, items)
        }
    }

    // ══ RECHERCHE ═════════════════════════════════════════════
    override suspend fun search(query: String): List<SearchResponse> {
        initMainUrl()
        val url = "$tmdbBase/search/multi?api_key=$tmdbApiKey&language=fr-FR&query=$query&include_adult=false"
        val data = parseJson<TmdbSearchResult>(app.get(url).text)
        return data.results.mapNotNull { item ->
            val isMovie = item.mediaType == "movie"
            val isSerie = item.mediaType == "tv"
            if (!isMovie && !isSerie) return@mapNotNull null
            
            val cleanName = (item.title ?: item.name ?: return@mapNotNull null)
                .lowercase()
                .replace(Regex("[^a-z0-9\\s]"), "")
                .replace(" ", "-")

            newMovieSearchResponse(
                item.title ?: item.name ?: return@mapNotNull null,
                "$mainUrl/${if (isMovie) "movies" else "tv-show"}/$cleanName/${item.id}",
                if (isMovie) TvType.Movie else TvType.TvSeries
            ) {
                this.posterUrl = item.posterPath?.let { "$imageBase$it" }
            }
        }
    }

    // ══ CHARGEMENT DÉTAILS ════════════════════════════════════
    override suspend fun load(url: String): LoadResponse {
        initMainUrl()
        
        // Nettoyage et extraction sécurisée de l'ID à la fin de l'adresse
        val tmdbId = url.trimEnd('/').substringAfterLast("/").toIntOrNull() 
            ?: throw ErrorLoadingException("ID TMDB invalide extrait depuis : $url")

        if (url.contains("anime")) {
            val isMovie = url.contains("-movie")
            val res = app.get("$mainUrl/api/public/v1/anime/$tmdbId", interceptor = cfKiller).text
            val item = (parseJson<Map<String, Any>>(res)["result"] as Map<*, *>)
            val title = item["title_fr"]?.toString() ?: item["title"]?.toString() ?: ""
            
            val rawPoster = item["poster"]?.toString()
            val finalPoster = if (!rawPoster.isNullOrBlank()) {
                if (rawPoster.startsWith("http")) rawPoster else "$imageBase$rawPoster"
            } else {
                getPosterFromTmdb(tmdbId.toString(), isMovie)
            }

            return if (isMovie) {
                newMovieLoadResponse(title, url, TvType.Anime, VideoLinkData(tmdbId, "movie").toJson()) {
                    this.posterUrl = finalPoster
                    this.plot = item["overview_fr"]?.toString()
                }
            } else {
                newTvSeriesLoadResponse(title, url, TvType.Anime, getTmdbEpisodes(tmdbId)) {
                    this.posterUrl = finalPoster
                    this.plot = item["overview_fr"]?.toString()
                }
            }
        }

        // Support complet de tous les types de préfixes d'URLs possibles (frembed anciens et nouveaux)
        val isMovie = url.contains("/film/") || url.contains("/movie") || url.contains("/movies/")
        
        return if (isMovie) {
            val detail = parseJson<TmdbMovieDetail>(app.get("$tmdbBase/movie/$tmdbId?api_key=$tmdbApiKey&language=fr-FR").text)
            newMovieLoadResponse(detail.title, url, TvType.Movie, VideoLinkData(tmdbId, "movie").toJson()) {
                this.posterUrl = detail.posterPath?.let { "$imageBase$it" }
                this.plot = detail.overview
            }
        } else {
            val detail = parseJson<TmdbSerieDetail>(app.get("$tmdbBase/tv/$tmdbId?api_key=$tmdbApiKey&language=fr-FR").text)
            newTvSeriesLoadResponse(detail.name, url, TvType.TvSeries, getTmdbEpisodes(tmdbId)) {
                this.posterUrl = detail.posterPath?.let { "$imageBase$it" }
                this.plot = detail.overview
            }
        }
    }

    private suspend fun getTmdbEpisodes(tmdbId: Int): List<Episode> {
        val episodesList = mutableListOf<Episode>()
        val detail = parseJson<TmdbSerieDetail>(app.get("$tmdbBase/tv/$tmdbId?api_key=$tmdbApiKey&language=fr-FR").text)
        detail.seasons?.filter { it.seasonNumber > 0 }?.forEach { season ->
            val sRes = app.get("$tmdbBase/tv/$tmdbId/season/${season.seasonNumber}?api_key=$tmdbApiKey&language=fr-FR").text
            parseJson<TmdbSeasonDetail>(sRes).episodes.forEach { ep ->
                episodesList.add(
                    newEpisode(VideoLinkData(tmdbId, "tv", season.seasonNumber, ep.episodeNumber).toJson()) {
                        this.name = ep.name
                        this.season = season.seasonNumber
                        this.episode = ep.episodeNumber
                    }
                )
            }
        }
        return episodesList
    }

    // ══ CHARGEMENT DES LIENS ══════════════════════════════════
	override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val linkData = parseJson<VideoLinkData>(data)
    var found = false
    val servers = listOf("link1", "link2", "link3", "link4", "link5", "link6", "link7")
    val type = if (linkData.type == "movie") "movie" else "tv"

    // Étape 1 : visiter la page de la fiche pour obtenir un Referer "valide" + cookies de session
    val pageUrl = if (type == "movie") {
        "$mainUrl/movies/film/${linkData.tmdbId}"
    } else {
        "$mainUrl/tv-show/from/${linkData.tmdbId}"
    }

    var sessionCookies = ""
    try {
        val pageResponse = app.get(
            pageUrl,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
            ),
            interceptor = cfKiller  // ici le CF killer a du sens, car c'est la vraie page HTML
        )
        sessionCookies = pageResponse.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        Log.d(TAG, "Cookies récupérés depuis $pageUrl: $sessionCookies")
    } catch (e: Exception) {
        Log.e(TAG, "Erreur récupération page pour cookies: ${e.message}")
    }

    servers.forEach { server ->
        try {
            val apiUrl = if (type == "movie") {
                "$mainUrl/api/stream?type=movie&tmdb=${linkData.tmdbId}&server=$server"
            } else {
                val s = linkData.season ?: 1
                val e = linkData.episode ?: 1
                "$mainUrl/api/stream?type=tv&tmdb=${linkData.tmdbId}&sea=$s&epi=$e&server=$server"
            }

            val response = app.get(
                apiUrl,
                headers = mapOf(
                    "Referer" to pageUrl,  // le Referer pointe vers la PAGE réelle, pas juste le domaine
                    "Origin" to mainUrl,
                    "Cookie" to sessionCookies,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
                    "Accept" to "application/json, text/plain, */*",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            )

            val htmlText = response.text
            Log.d(TAG, "Status pour $server: ${response.code} | Taille: ${htmlText.length}")
            Log.d(TAG, "htmlText pour $server: $htmlText")

            val targetUrl = extractStreamUrl(htmlText)
            if (targetUrl?.startsWith("http") == true) {
                if (loadExtractor(targetUrl, mainUrl, subtitleCallback, callback)) {
                    found = true
                } else if (targetUrl.contains(".m3u8") || targetUrl.contains(".mp4")) {
                    val isM3u8 = targetUrl.contains(".m3u8")
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name - $server",
                            url = targetUrl,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    found = true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lors du chargement du serveur $server: ${e.message}", e)
        }
    }
    return found
}

    private fun extractStreamUrl(response: String): String? {
    val t = response.trim()

    // 1. JSON
    if (t.startsWith("{")) {
        if (t.contains("error")) return null
        return Regex(""""(?:url|stream|link|src)"\s*:\s*"([^"]+)"""")
            .find(t)?.groupValues?.get(1)?.replace("\\/", "/")
    }

    // 2. DoodStream - AVANT le bloc HTML
    val doodFileId = Regex("""['"](file_id)['"]\s*,\s*['"]([^'"]+)['"]""")
        .find(t)?.groupValues?.get(2)
    Log.d(TAG, "doodFileId extrait: $doodFileId")
    if (doodFileId != null) {
        return "https://dood.li/e/$doodFileId"
    }

    // 3. HTML classique
    if (t.contains("<html", ignoreCase = true) || t.contains("<!DOCTYPE", ignoreCase = true)) {
        if (t.contains("no longer available") || t.contains("expired")) return null
        // NE PAS checker "404" ici car ça peut apparaître dans d'autres contextes

        val patterns = listOf(
            """property=["']og:video:secure_url["'][^>]+content=["']([^"']+)["']""",
            """property=["']og:video["'][^>]+content=["']([^"']+)["']""",
            """<iframe[^>]+src=["']([^"']+)["']""",
            """let\s+(?:url|link|src)\s*=\s*["']([^"']+)["']""",
            """const\s+(?:url|link|src)\s*=\s*["']([^"']+)["']""",
            """window\.location\.href\s*=\s*["']([^"']+)["']"""
        )
        for (p in patterns) {
            val url = Regex(p, RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)
            if (!url.isNullOrBlank() && url.startsWith("http")) {
                return url.replace("\\/", "/")
            }
        }
    }

    return if (t.replace("\"", "").startsWith("http"))
        t.replace("\"", "").replace("\\/", "/")
    else null
}
}