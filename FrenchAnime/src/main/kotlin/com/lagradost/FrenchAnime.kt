package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.nicehttp.NiceResponse
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.util.*
import kotlin.collections.ArrayList

class FrenchAnime : MainAPI() {
    override var mainUrl = "https://french-anime.com" 
    override var name = "03- \u2b50 French Anime \u2b50"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    private val interceptor = CloudflareKiller()
    
    // Data class pour les données de chargement
    data class LoadLinkData(
        @JsonProperty("url") val url: String,
        @JsonProperty("episodeNumber") val episodeNumber: String? = null,
        @JsonProperty("isVostfr") val isVostfr: Boolean = false
    )
    
    init {
        if (mainUrl.endsWith("/")) mainUrl = mainUrl.dropLast(1)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/index.php?do=search&subaction=search&search_start=0&full_search=1&result_from=1&story=$query&titleonly=3&searchuser=&replyless=0&replylimit=0&searchdate=0&beforeafter=after&sortby=date&resorder=desc&showposts=0&catlist%5B%5D=0"
        
        val document = app.post(link).document
        val results = document.select("div#dle-content > div.mov.clearfix")
        return results.mapNotNull { article ->
            article.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = avoidCloudflare(url).document
        
        // Titre
        val title = document.select("h1[itemprop]").text()
        
        // Poster
        val posterUrl = document.select("img#posterimg").attr("src")
        
        // Année
        val year = document.select("div.mov-desc:contains(Date de sortie)").firstOrNull()
            ?.text()?.substringAfter(":")?.trim()?.toIntOrNull()
        
        // Description
        val description = document.select("div.mov-desc:contains(Synopsis)").firstOrNull()
            ?.text()?.substringAfter(":")?.trim()
        
        // Version (VF ou VOSTFR)
        val version = document.select("div.mov-desc:contains(Version)").firstOrNull()
            ?.text()?.lowercase() ?: ""
        val isVostfr = version.contains("vostfr")
        
        // Récupérer TOUTES les sections d'épisodes
        val epsSections = document.select("div.eps")
        
        if (epsSections.isEmpty()) {
            // C'est un film
            return newMovieLoadResponse(title, url, TvType.Movie, LoadLinkData(url, isVostfr = isVostfr).toJson()) {
                this.posterUrl = fixUrl(posterUrl)
                this.plot = description
                this.year = year
                // Ajouter des tags pour indiquer VF/VOSTFR
                this.tags = listOf(if (isVostfr) "VOSTFR" else "VF")
                // Ajouter la bande-annonce si disponible
                addTrailer(document.selectFirst("a[href*='youtube']")?.attr("href"))
            }
        }
        
        // Traitement pour les séries
        val subEpisodes = ArrayList<Episode>()
        val dubEpisodes = ArrayList<Episode>()
        
        // Pour chaque section d'épisodes
        epsSections.forEach { epsSection ->
            val epsContent = epsSection.text()
            
            // Analyser le contenu avec regex
            // Format: "1!url1,url2, 2!url1,url2, 3!url1,url2, ..."
            val episodePattern = Regex("""(\d+)!(.*?)(?=\s*\d+!|$)""")
            val matches = episodePattern.findAll(epsContent)
            
            matches.forEach { match ->
                val epNumStr = match.groupValues[1]
                val urlsPart = match.groupValues[2]
                
                // Nettoyer les URLs
                val urls = urlsPart.trim().trimEnd(',').split(',')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                
                val epNum = epNumStr.toIntOrNull()
                if (epNum != null && urls.isNotEmpty()) {
                    val episodeData = LoadLinkData(
                        url = url,
                        episodeNumber = epNumStr,
                        isVostfr = isVostfr
                    ).toJson()
                    
                    if (isVostfr) {
                        subEpisodes.add(
                            newEpisode(episodeData) {
                                this.name = "Episode $epNum (VOSTFR)"
                                this.episode = epNum
                                this.posterUrl = fixUrl(posterUrl)
                            }
                        )
                    } else {
                        dubEpisodes.add(
                            newEpisode(episodeData) {
                                this.name = "Episode $epNum (VF)"
                                this.episode = epNum
                                this.posterUrl = fixUrl(posterUrl)
                            }
                        )
                    }
                }
            }
        }
        
        // Recommendations
        val recommendations = document.select("div.clearfixme > div > div").mapNotNull { element ->
            val recTitle = element.select("a").text()?.trim() ?: return@mapNotNull null
            val image = element.select("a > img").attr("src")
            val recUrl = element.select("a").attr("href")
            val type = if (recUrl.contains("film")) TvType.Movie else TvType.TvSeries

            if (type == TvType.TvSeries) {
                newTvSeriesSearchResponse(recTitle, recUrl) {
                    this.posterUrl = image?.let { fixUrl(it) }
                }
            } else {
                newMovieSearchResponse(recTitle, recUrl) {
                    this.posterUrl = image?.let { fixUrl(it) }
                }
            }
        }

        // Tags pour indiquer les versions disponibles
        val tagsList = mutableListOf<String>()
        if (dubEpisodes.isNotEmpty()) tagsList.add("VF")
        if (subEpisodes.isNotEmpty()) tagsList.add("VOSTFR")

        return newAnimeLoadResponse(title, url, TvType.TvSeries) {
            this.posterUrl = fixUrl(posterUrl)
            this.plot = description
            this.recommendations = recommendations
            this.year = year
            this.tags = tagsList
            
            // Ajouter la bande-annonce si disponible
            addTrailer(document.selectFirst("a[href*='youtube']")?.attr("href"))
            
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsedData = tryParseJson<LoadLinkData>(data) ?: return false
        
        val url = parsedData.url
        val episodeNumStr = parsedData.episodeNumber
        
        // Si c'est un film (pas de numéro d'épisode)
        if (episodeNumStr == null) {
            return loadExtractor(url, mainUrl, subtitleCallback, callback)
        }
        
        // C'est une série
        val episodeNum = episodeNumStr.toIntOrNull() ?: return false
        
        val document = avoidCloudflare(url).document
        
        // Chercher dans TOUTES les sections d'épisodes
        val epsSections = document.select("div.eps")
        
        var foundLinks: List<String>? = null
        
        for (epsSection in epsSections) {
            val epsContent = epsSection.text()
            
            // Utiliser une regex pour trouver l'épisode spécifique
            val episodePattern = Regex("""${episodeNumStr}!(.*?)(?=\s*\d+!|$)""")
            val match = episodePattern.find(epsContent)
            
            if (match != null) {
                val urlsPart = match.groupValues[1]
                // Nettoyer et extraire les URLs
                foundLinks = urlsPart.trim().trimEnd(',').split(',')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                break
            }
        }
        
        if (foundLinks.isNullOrEmpty()) {
            return false
        }

        var success = false
        foundLinks.forEach { playerUrl ->
            val fixedUrl = when {
                playerUrl.contains("vidmoly") -> playerUrl.replace("vidmoly.net/embed-", "vidmoly.to/")
                else -> playerUrl
            }

            if (loadExtractor(fixedUrl, mainUrl, subtitleCallback, callback)) {
                success = true
            }
        }
        
        return success
    }

    private fun Element.toSearchResponse(): SearchResponse? {
    val link = selectFirst("a.mov-t.nowrap")?.attr("href")?.let { fixUrl(it) } ?: return null
    val baseTitle = selectFirst("a.mov-t.nowrap")?.text()?.trim() ?: return null
    
    // Poster avec priorité data-src puis src
    val posterUrl = selectFirst("div.mov-i img")?.attr("data-src")?.takeIf { it.isNotEmpty() }
        ?: selectFirst("div.mov-i img")?.attr("src")
        ?.let { fixUrl(it) }

    val blockSai = selectFirst("span.block-sai")?.text()?.lowercase() ?: ""
    val isVF = blockSai.contains("french") || blockSai.contains("truefrench") || 
               select("span.nbloc1").text().lowercase().contains("french")
    val isVOSTFR = blockSai.contains("vostfr") || 
                   select("span.nbloc1").text().lowercase().contains("vostfr")

    val saisonText = Regex("""saison\s*(\d+)""", RegexOption.IGNORE_CASE)
        .find(blockSai)?.groupValues?.getOrNull(1)
    val saisonPart = saisonText?.let { " - Saison $it" } ?: ""

    val fullTitle = baseTitle + saisonPart

    val qualityRaw = select("span.nbloc2").text().trim()
    val quality = getQualityFromString(
        when {
            qualityRaw.contains("HDLight", true) -> "HD"
            qualityRaw.contains("Bdrip", true) -> "BluRay"
            qualityRaw.contains("DVD", true) -> "DVD"
            qualityRaw.contains("CAM", true) -> "CAM"
            else -> qualityRaw
        }
    )

    val isMovie = select("div.nbloc3").text().lowercase().contains("film") || saisonText == null

    if (isMovie) {
        return newMovieSearchResponse(fullTitle, link) {
            this.posterUrl = posterUrl
            this.quality = quality
        }
    } else {
        val epText = select("div.block-ep").text()
        val episodeNumber = Regex("""pisode\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()

        return newAnimeSearchResponse(fullTitle, link, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.quality = quality
            addDubStatus(isDub = isVF, episodes = episodeNumber)
        }
    }
}

    suspend fun avoidCloudflare(url: String): NiceResponse {
        if (!app.get(url).isSuccessful) {
            return app.get(url, interceptor = interceptor)
        } else {
            return app.get(url)
        }
    }

    override val mainPage = mainPageOf(
        "/animes-vf/page/" to "Animes VF",
        "/animes-vostfr/page/" to "Animes VOSTFR",
        "/films-vf-vostfr/page/" to "Films"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}$page"
        val document = avoidCloudflare(url).document
        
        val movies = document.select("div#dle-content > div.mov.clearfix")
        val home = movies.mapNotNull { article ->
            article.toSearchResponse()
        }
        
        return newHomePageResponse(request.name, home)
    }
}