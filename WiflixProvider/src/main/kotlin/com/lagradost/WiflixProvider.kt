package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.nicehttp.NiceResponse
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

import kotlin.collections.ArrayList
import java.util.*

class WiflixProvider : MainAPI() {

    override var mainUrl = "https://flemmix.bond"
    override var name = "01- \u2b50 Wiflix \u2b50"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    private val interceptor = CloudflareKiller()

    suspend fun avoidCloudflare(url: String): NiceResponse {
        return if (!app.get(url).isSuccessful) {
            app.get(url, interceptor = interceptor)
        } else {
            app.get(url)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/index.php?do=search&subaction=search&search_start=0&full_search=1&result_from=1&story=$query&titleonly=3&searchuser=&replyless=0&replylimit=0&searchdate=0&beforeafter=after&sortby=date&resorder=desc&showposts=0&catlist%5B%5D=0"
        val document = app.post(link).document
        val results = document.select("div#dle-content > div.clearfix")

        return results.mapNotNull { article ->
            article.toSearchResponse()
        }
    }

    // Nouvelle structure de données pour les liens
    data class LoadLinkData(
        val embedUrl: String,
        val isVostFr: Boolean? = null,
        val episodenumber: Int? = null
    )

    // Fonction pour créer les épisodes avec les nouvelles variables
    private fun Elements.takeEpisodeFromBloc(
        url: String,
        isVostFr: Boolean
    ): List<Episode> {
        return this.select("ul.eplist > li").mapNotNull { li ->
            val epNum = Regex("""pisode[\s]+(\d+)""").find(li.text())?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            
            val title = if (isVostFr) "Episode $epNum Vostfr 📜 🇬🇧" 
                       else "Episode $epNum VF 🇫🇷"
            
            newEpisode(
                LoadLinkData(
                    embedUrl = fixUrl(url),
                    isVostFr = isVostFr,
                    episodenumber = epNum
                ).toJson()
            ) {
                this.name = title
                this.episode = epNum
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val soup = avoidCloudflare(url).document
        var subEpisodes = listOf<Episode>()
        var dubEpisodes = listOf<Episode>()

        val title = soup.selectFirst("h1[itemprop]")?.text() ?: ""
        
        val poster = soup.selectFirst("img#posterimg")?.attr("src")?.takeIf { it.isNotEmpty() }
            ?: soup.selectFirst("div.img-box > img")?.attr("src")?.takeIf { it.isNotEmpty() }
        
        val episodeFrfound = soup.select("div.blocfr")
        val episodeVostfrfound = soup.select("div.blocvostfr")
        
        val isMovie = episodeFrfound.isEmpty() && episodeVostfrfound.isEmpty()

        // Extraction de l'année
        val yearRegex = Regex("""ate de sortie\: (\d*)""")
        val year = yearRegex.find(soup.text())?.groupValues?.get(1)?.toIntOrNull()

        // Extraction des tags avec formatage
        val tags = soup.select("[itemprop=genre] > a").mapNotNull { element ->
            val tagText = element.text()
            when {
                tagText.contains("VF", ignoreCase = true) -> "(Dub\u2335)VF \uD83C\uDDE8\uD83C\uDDF5"
                tagText.contains("VOSTFR", ignoreCase = true) -> "(Sub\u2335)Vostfr \uD83D\uDCDC \uD83C\uDDEC\uD83C\uDDE7"
                else -> tagText
            }
        }.toMutableList()

        if (isMovie) {
            val description = soup.selectFirst("div.screenshots-full")?.text()
                ?.substringAfterLast(":") ?: ""
            
            // Vérifier si c'est VOSTFR
            val isVostfr = soup.select("span[itemprop*=\"inLanguage\"]")
                .text().contains("vostfr", ignoreCase = true)

            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                LoadLinkData(url, isVostFr = isVostfr)
            ) {
                this.posterUrl = poster?.let { fixUrl(it) }
                this.plot = description
                this.year = year
                this.tags = tags
                // Ajouter les recommandations si disponibles
                val recommendations = getRecommendations(soup)
                if (recommendations.isNotEmpty()) {
                    this.recommendations = recommendations
                }
            }
        } else {
            // C'est une série
            dubEpisodes = episodeFrfound.takeEpisodeFromBloc(url, false)
            subEpisodes = episodeVostfrfound.takeEpisodeFromBloc(url, true)

            // Ajouter des tags pour indiquer les langues disponibles
            if (dubEpisodes.isNotEmpty()) {
                tags.add("(Dub\u2335)VF \uD83C\uDDE8\uD83C\uDDF5")
            }
            if (subEpisodes.isNotEmpty()) {
                tags.add("(Sub\u2335)Vostfr \uD83D\uDCDC \uD83C\uDDEC\uD83C\uDDE7")
            }

            val description = soup.selectFirst("span[itemprop=description]")?.text()
                ?.substringAfterLast(":") ?: ""

            return newAnimeLoadResponse(
                title,
                url,
                TvType.TvSeries
            ) {
                this.posterUrl = poster?.let { fixUrl(it) }
                this.plot = description
                this.year = year
                this.tags = tags
                // Ajouter les recommandations si disponibles
                val recommendations = getRecommendations(soup)
                if (recommendations.isNotEmpty()) {
                    this.recommendations = recommendations
                }
                
                if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
                if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            }
        }
    }

    // Fonction pour extraire les recommandations
    private fun getRecommendations(soup: org.jsoup.nodes.Document): List<SearchResponse> {
        return soup.select("div.clearfixme > div > div").mapNotNull { element ->
            val recTitle = element.select("a").text().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val image = element.select("a > img").attr("src").takeIf { it.isNotEmpty() }
            val recUrl = element.select("a").attr("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            
            val isMovie = recUrl.contains("film")
            val type = if (isMovie) TvType.Movie else TvType.TvSeries
            
            if (isMovie) {
                // CORRECTION : Utiliser newMovieSearchResponse au lieu du constructeur
                newMovieSearchResponse(recTitle, recUrl, TvType.Movie) {
                    this.posterUrl = image?.let { fixUrl(it) }
                }
            } else {
                newAnimeSearchResponse(recTitle, recUrl, TvType.TvSeries) {
                    this.posterUrl = image?.let { fixUrl(it) }
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsedData = tryParseJson<LoadLinkData>(data) ?: return false
        val url = fixUrl(parsedData.embedUrl)
        
        val document = avoidCloudflare(url).document
        
        // Déterminer le sélecteur CSS selon le type (film ou série)
        val cssSelector = if (parsedData.episodenumber != null) {
            // Série
            val epNum = parsedData.episodenumber
            if (parsedData.isVostFr == true) {
                "div.ep${epNum}vs > a"
            } else {
                "div.ep${epNum}vf > a"
            }
        } else {
            // Film
            "div.tabs-sel.linkstab > div.tabs-sel.linkstab > a"
        }
        
        // Extraire les liens vidéo
        val links = mutableListOf<String>()
        
        document.select(cssSelector).forEach { player ->
            var playerUrl = player.attr("onclick")
                .substringAfter("loadVideo(&#39;")
                .substringBefore("&#39;")
                .substringAfter("loadVideo('")
                .substringBefore("')")
                .takeIf { it.isNotBlank() }
            
            if (playerUrl != null) {
                // Correction pour doodstream
                if (playerUrl.contains("dood") || playerUrl.contains("d00")) {
                    playerUrl = playerUrl.replace("doodstream.com", "dood.wf")
                }
                links.add(playerUrl)
            }
        }
        
        // Si pas de liens trouvés avec le premier sélecteur, essayer le fallback pour les films
        if (links.isEmpty() && parsedData.episodenumber == null) {
            document.select("div.tabs-sel.linkstab > div.tabs-sel.linkstab > a").forEach { player ->
                var playerUrl = player.attr("onclick")
                    .substringAfter("loadVideo(&#39;")
                    .substringBefore("&#39;")
                    .substringAfter("loadVideo('")
                    .substringBefore("')")
                    .takeIf { it.isNotBlank() }
                
                if (playerUrl != null) {
                    if (playerUrl.contains("dood") || playerUrl.contains("d00")) {
                        playerUrl = playerUrl.replace("doodstream.com", "dood.wf")
                    }
                    links.add(playerUrl)
                }
            }
        }
        
        // Charger chaque lien via les extractors
        if (links.isNotEmpty()) {
            links.distinct().forEach { link ->
                loadExtractor(httpsify(link), mainUrl, subtitleCallback, callback)
            }
            return true
        }
        
        return false
    }

    private fun Element.toSearchResponse(): SearchResponse {
        val posterUrl = select("div.img-box > img").attr("src").takeIf { it.isNotEmpty() }
        val qualityExtracted = select("div.nbloc1-2 > span").text()
        val type = select("div.nbloc3").text().lowercase()
        val titlefirst = select("a.nowrap").text()
        val seasonAndLanguage = select("span.block-sai").text()
        val title = if (seasonAndLanguage.isNotEmpty()) "$titlefirst\n$seasonAndLanguage" else titlefirst
        val link = select("a.nowrap").attr("href").takeIf { it.isNotEmpty() } ?: return newMovieSearchResponse("", "", TvType.Movie) {}
        
        val quality = getQualityFromString(
            when {
                qualityExtracted.contains("HDLight", ignoreCase = true) -> "HD"
                qualityExtracted.contains("Bdrip", ignoreCase = true) -> "BlueRay"
                qualityExtracted.contains("DVD", ignoreCase = true) -> "DVD"
                qualityExtracted.contains("CAM", ignoreCase = true) -> "Cam"
                else -> null
            }
        )
        
        if (type.contains("film")) {
            val isVostfr = select("span.nbloc1").text().contains("vostfr", ignoreCase = true)
            
            // CORRECTION : Pour les films, on ne peut pas utiliser dubStatus avec newMovieSearchResponse
            // On utilise addDubStatus seulement pour les séries
            return newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = posterUrl?.let { fixUrl(it) }
                this.quality = quality
                // Note: newMovieSearchResponse n'a pas de champ dubStatus
                // On pourrait ajouter l'info dans le titre ou les tags si nécessaire
            }
        } else {
            // C'est une série
            val episodeText = select("div.block-ep").text()
            val episodeCount = Regex("""pisode[\s]+(\d+)""").find(episodeText)?.groupValues?.get(1)?.toIntOrNull()
            val isDub = !select("span.block-sai").text().uppercase().contains("VOSTFR")
            
            return newAnimeSearchResponse(title, link, TvType.TvSeries) {
                this.posterUrl = posterUrl?.let { fixUrl(it) }
                this.quality = quality
                // CORRECTION : Utiliser addDubStatus pour les séries
                addDubStatus(isDub = isDub, episodes = episodeCount)
            }
        }
    }

    override val mainPage = mainPageOf(
        Pair("/film-en-streaming/page/", "Top Films cette année"),
        Pair("/serie-en-streaming/page/", "Top Séries cette année"),
        Pair("/saison-complete/page/", "Les saisons complètes"),
        Pair("/film-ancien/page/", "Anciens Films"),
        Pair("/film-en-streaming/historique/page/", "Historique"),
        Pair("/film-en-streaming/famille/page/", "Famille"),
        Pair("/film-en-streaming/comedie/page/", "Comédie"),
        Pair("/film-en-streaming/drame/page/", "Drame"),
        Pair("/film-en-streaming/fantastique/page/", "Fantastique"),
        Pair("/film-en-streaming/science-fiction/page/", "Science Fiction"),
        Pair("/film-en-streaming/aventure/page/", "Aventure"),
        Pair("/film-en-streaming/animation/page/", "Animation")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = mainUrl + request.data + page
        val document = avoidCloudflare(url).document
        val movies = document.select("div#dle-content > div.clearfix")

        val home = movies.mapNotNull { element ->
            element.toSearchResponse()
        }

        return newHomePageResponse(request.name, home)
    }
}