package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import kotlin.collections.ArrayList
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.cloudstream3.extractors.*

import java.util.*

class FrenchAnime : MainAPI() {
    override var mainUrl = "https://french-anime.com" 
    override var name = "03- \u2b50 French Anime \u2b50"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    private val interceptor = CloudflareKiller()
	
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

data class EpisodeData(
    @JsonProperty("url") val url: String,
    @JsonProperty("episodeNumber") val episodeNumber: String, // Reste en String pour compatibilité
    @JsonProperty("isVostfr") val isVostfr: Boolean = false
)

    private fun Elements.takeEpisode(
        url: String,
        posterUrl: String?,
        duborSub: String?
    ): ArrayList<Episode> {
        val episodes = ArrayList<Episode>()
        this.select("ul.eplist > li").forEach {
            val strEpisodeN = Regex("""pisode[\s]+(\d+)""").find(it.text())?.groupValues?.get(1).toString()
            val link = EpisodeData(url, strEpisodeN).toJson()

            episodes.add(
                Episode(
                    link + if (duborSub == "vostfr") "*$duborSub*" else "VF",
                    name = "Episode en $duborSub",
                    episode = strEpisodeN.toIntOrNull(),
                    posterUrl = posterUrl
                )
            )
        }
        return episodes
    }

   override suspend fun load(url: String): LoadResponse {
    val document = avoidCloudflare(url).document
    val title = document.select("h1[itemprop]").text()
    val posterUrl = document.select("img#posterimg").attr("src")
    val year = document.select("div.mov-desc:contains(Date de sortie)").firstOrNull()?.text()?.trim()?.toIntOrNull()
    val description = document.select("div.mov-desc:contains(Synopsis)").firstOrNull()?.text()
    
    val version = document.select("div.mov-desc:contains(Version)").firstOrNull()?.text()?.lowercase() ?: ""
    val isVostfr = version.contains("vostfr")
    
    val epsContent = document.selectFirst("div.eps")?.text()
    val episodesList = epsContent?.split("\n")
        ?.filter { it.contains("!") }
        ?.mapNotNull { line ->
            val parts = line.split("!")
            if (parts.size >= 2) {
                val epNum = parts[0].trim()
                val links = parts[1].split(",").map { it.trim() }
                epNum to links
            } else null
        } ?: emptyList()
    
    val mediaType = if (episodesList.isEmpty()) TvType.Movie else TvType.TvSeries
    
    val subEpisodes = ArrayList<Episode>()
    val dubEpisodes = ArrayList<Episode>()
    
    episodesList.forEach { (epNumStr, links) ->
        val epNum = epNumStr.toIntOrNull() ?: return@forEach
        
        val episodeData = EpisodeData(
            url = url,
            episodeNumber = epNumStr, // On garde le String original
            isVostfr = isVostfr
        ).toJson()
        
        if (isVostfr) {
            subEpisodes.add(
                Episode(
                    data = episodeData,
                    name = "Episode $epNum (VOSTFR)",
                    episode = epNum, // Ici on utilise l'Int pour le numéro d'épisode
                    posterUrl = fixUrl(posterUrl)
                )
            )
        } else {
            dubEpisodes.add(
                Episode(
                    data = episodeData,
                    name = "Episode $epNum (VF)",
                    episode = epNum, // Ici on utilise l'Int pour le numéro d'épisode
                    posterUrl = fixUrl(posterUrl)
                )
            )
        }
    }


    // Recommendations
    val recommendations = document.select("div.clearfixme > div > div").mapNotNull { element ->
        val recTitle = element.select("a").text() ?: return@mapNotNull null
        val image = element.select("a > img").attr("src")
        val recUrl = element.select("a").attr("href")
        val type = if (recUrl.contains("film")) TvType.Movie else TvType.TvSeries

        if (type == TvType.TvSeries) {
            TvSeriesSearchResponse(recTitle, recUrl, name, type, image?.let { fixUrl(it) })
        } else {
            MovieSearchResponse(recTitle, recUrl, name, type, image?.let { fixUrl(it) })
        }
    }

    return if (mediaType == TvType.Movie) {
        newMovieLoadResponse(title, url, TvType.Movie, url + if (isVostfr) "*vostfr*" else "") {
            this.posterUrl = fixUrl(posterUrl)
            this.plot = description
            this.recommendations = recommendations
            this.year = year
        }
    } else {
        newAnimeLoadResponse(title, url, TvType.TvSeries) {
            this.posterUrl = fixUrl(posterUrl)
            this.plot = description
            this.recommendations = recommendations
            this.year = year
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }
}
    
private fun parseData(data: String): Pair<String, String> {
    return try {
        val parsedInfo = tryParseJson<EpisodeData>(data) ?: throw Exception("Invalid data format")
        Pair(parsedInfo.url, parsedInfo.episodeNumber) // Retourne Pair<String, String>
    } catch (e: Exception) {
        // Fallback pour la compatibilité
        if (data.contains("|")) {
            val parts = data.split("|")
            val url = parts[0]
            val epNum = parts.getOrNull(1) ?: "1"
            Pair(url, epNum)
        } else {
            Pair(data, "1") // Valeur par défaut si tout échoue
        }
    }
}

override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val (url, episodeNumStr) = parseData(data) // episodeNumStr est un String
    val episodeNum = episodeNumStr.toIntOrNull() ?: 1 // Conversion en Int pour l'affichage
    
    val document = avoidCloudflare(url).document
    val epsContent = document.selectFirst("div.eps")?.text() ?: return false
    
    val episodeLine = epsContent.split("\n")
        .find { it.startsWith("$episodeNumStr!") } ?: return false
        
    val episodeLinks = episodeLine.substringAfter("!")
        .split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }

    episodeLinks.forEach { playerUrl ->
        val fixedUrl = when {
            playerUrl.contains("vidmoly") -> playerUrl.replace("vidmoly.net/embed-", "vidmoly.to/")
            else -> playerUrl
        }

        loadExtractor(fixedUrl, fixedUrl, subtitleCallback) { link ->
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "${link.name} (Episode $episodeNum)", // Utilise l'Int converti pour l'affichage
                    url = link.url,
                    referer = link.referer,
                    quality = getQualityFromName(link.name),
                    isM3u8 = link.isM3u8
                )
            )
        }
    }
    return true
}

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = selectFirst("a.mov-t.nowrap")?.attr("href")?.let { fixUrl(it) } ?: return null
        val baseTitle = selectFirst("a.mov-t.nowrap")?.text()?.trim() ?: return null
        val posterUrl = selectFirst("div.mov-i img")?.attr("src")?.let { fixUrl(it) }

        val blockSai = selectFirst("span.block-sai")?.text()?.lowercase() ?: ""
        val isVF = blockSai.contains("french") || blockSai.contains("truefrench") || select("span.nbloc1").text().lowercase().contains("french")
        val isVOSTFR = blockSai.contains("vostfr") || select("span.nbloc1").text().lowercase().contains("vostfr")

        val saisonText = Regex("""saison\s*(\d+)""", RegexOption.IGNORE_CASE).find(blockSai)?.groupValues?.getOrNull(1)
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

        return if (isMovie) {
            newAnimeSearchResponse(fullTitle, link, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = quality
                this.dubStatus = when {
                    isVOSTFR -> EnumSet.of(DubStatus.Subbed)
                    isVF -> EnumSet.of(DubStatus.Dubbed)
                    else -> EnumSet.noneOf(DubStatus::class.java)
                }
            }
        } else {
            val epText = select("div.block-ep").text()
            val episodeNumber = Regex("""pisode\s*(\d+)""", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()

            newAnimeSearchResponse(fullTitle, link, TvType.TvSeries) {
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
        	Pair("/animes-vf/page/", "Animes VF"),
        	Pair("/animes-vostfr/page/", "Animes VOSTFR"),
        	Pair("/genre/action/page/", "Action"),
			Pair("/genre/aventure/page/", "Aventure"),
			Pair("/genre/arts-martiaux/page/", "Arts martiaux"),
			Pair("/genre/combat/page/", "Combat"),
			Pair("/genre/comedie/page/", "Comédie"),
			Pair("/genre/drame/page/", "Drame"),
			Pair("/genre/epouvante/page/", "Epouvante"),
			Pair("/genre/fantastique/page/", "Fantastique"),
			Pair("/genre/fantasy/page/", "Fantasy"),
			Pair("/genre/mystere/page/", "Mystère"),
			Pair("/genre/romance/page/", "Romance"),
			Pair("/genre/shonen/page/", "Shonen"),
			Pair("/genre/surnaturel/page/", "Surnaturel"),
			Pair("/genre/sci-fi/page/", "Sci-Fi"),
			Pair("/genre/school-life/page/", "School life"),
			Pair("/genre/ninja/page/", "Ninja"),
			Pair("/genre/seinen/page/", "Seinen"),
			Pair("/genre/horreur/page/", "Horreur"),
			Pair("/genre/tranchedevie/page/", "Tranche de vie"),
			Pair("/genre/psychologique/page/", "Psychologique")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val categories = listOf(
            Pair("/animes-vf/page/", "Animes VF"),
            Pair("/animes-vostfr/page/", "Animes VOSTFR"),
			Pair("/genre/action/page/", "Action"),
			Pair("/genre/aventure/page/", "Aventure"),
			Pair("/genre/arts-martiaux/page/", "Arts martiaux"),
			Pair("/genre/combat/page/", "Combat"),
			Pair("/genre/comedie/page/", "Comédie"),
			Pair("/genre/drame/page/", "Drame"),
			Pair("/genre/epouvante/page/", "Epouvante"),
			Pair("/genre/fantastique/page/", "Fantastique"),
			Pair("/genre/fantasy/page/", "Fantasy"),
			Pair("/genre/mystere/page/", "Mystère"),
			Pair("/genre/romance/page/", "Romance"),
			Pair("/genre/shonen/page/", "Shonen"),
			Pair("/genre/surnaturel/page/", "Surnaturel"),
			Pair("/genre/sci-fi/page/", "Sci-Fi"),
			Pair("/genre/school-life/page/", "School life"),
			Pair("/genre/ninja/page/", "Ninja"),
			Pair("/genre/seinen/page/", "Seinen"),
			Pair("/genre/horreur/page/", "Horreur"),
			Pair("/genre/tranchedevie/page/", "Tranche de vie"),
			Pair("/genre/psychologique/page/", "Psychologique")

        )

        val sections = categories.map { (path, title) ->
            HomePageList(title, loadCategory(mainUrl + path + page))
        }

        return HomePageResponse(sections)
    }

    private val categorySelectors = listOf(
        "div#dle-content > div.mov.clearfix",
        "div.block-main > div.mov.clearfix",
        "div.mov.clearfix",
        "div#grid.floaters.clearfix.grid.grid-thumb div.mov.clearfix"
    )

    private suspend fun loadCategory(url: String): List<SearchResponse> {
        val doc = avoidCloudflare(url).document
        for (sel in categorySelectors) {
            val elems = doc.select(sel)
            if (elems.isNotEmpty()) {
                return elems.mapNotNull { it.toSearchResponse() }
            }
        }
        return emptyList()
    }
}