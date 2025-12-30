package com.lagradost

import com.lagradost.nicehttp.NiceResponse
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.extractorApis
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

import kotlin.collections.ArrayList
import java.util.*

class FsMirrorLol : MainAPI() {
    override var mainUrl = "https://fsmirror46.lol" //"https://vvw.french-stream.bio" //re ou ac ou city
    override var name = "02- \ud83d\udd25 FsMirrorLol Mirror\u269c\ufe0f"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    private val interceptor = CloudflareKiller()

    private var isNotInit = true

    suspend fun initMainUrl() {
        try {
            val document = avoidCloudflare(mainUrl).document
            val newMainUrl = document.select("link[rel*=\"canonical\"]").attr("href")
            if (!newMainUrl.isNullOrBlank() && newMainUrl.contains("french-stream")) {
                mainUrl = newMainUrl
            } else {
                // Récupérer l'URL depuis le fichier JSON
                val mediaDataList = app.get("https://amsc.duckdns.org/cloudstream/repo/urls.json")
                    .parsed<ArrayList<mediaData>>()

                mediaDataList.forEach { mediaData ->
                    if (mediaData.title.equals("French-stream", ignoreCase = true)) {
                        mainUrl = mediaData.url
                        return@forEach
                    }
                }
            }
        } catch (e: Exception) {
            // En cas d'erreur lors de la récupération de l'URL canonique ou du fichier JSON, gérer l'exception
            val mediaDataList = app.get("https://amsc.duckdns.org/cloudstream/repo/urls.json")
                .parsed<ArrayList<mediaData>>()

            mediaDataList.forEach { mediaData ->
                if (mediaData.title.equals("French-stream", ignoreCase = true)) {
                    mainUrl = mediaData.url
                    return@forEach
                }
            }
        }

        // Assurez-vous que mainUrl ne termine pas par "/"
        if (mainUrl.endsWith("/")) mainUrl = mainUrl.dropLast(1)

        // Marquer l'initialisation comme complète
        isNotInit = false
    }

    suspend fun avoidCloudflare(url: String): NiceResponse {
        if (!app.get(url).isSuccessful) {
            return app.get(url, interceptor = interceptor)
        } else {
            return app.get(url)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/index.php?story=$query&do=search&subaction=search" // search'
        val document =
            app.post(link).document // app.get() permet de télécharger la page html avec une requete HTTP (get)
        val results = document.select("div#dle-content > div.short")

        val allresultshome =
            results.mapNotNull { article ->  // avec mapnotnull si un élément est null, il sera automatiquement enlevé de la liste
                article.toSearchResponse()
            }
        return allresultshome
    }
	
	///// STRUCTURE JSON des liens VIDEOS
		data class loadLinkData(
			val embedUrl: String,
			val isVostFr: Boolean? = null,
			val episodenumber: Int? = null,
			val allLinks: List<String> = emptyList()  // ← Ajouter cette ligne pour stocker tous les liens
		)

private fun Elements.takeEpisodeFromDivs(isVostFr: Boolean): List<Episode> {
    return this.mapNotNull { div ->
        val epNum = div.attr("data-ep").toIntOrNull() ?: return@mapNotNull null
        if (epNum <= 0) return@mapNotNull null

        // 🔥 Prendre TOUS les liens, pas juste le premier
        val allLinks = div.attributes()
            .asList()
            .filter {
                it.key.startsWith("data-")
                        && it.key != "data-ep"
                        && it.value.startsWith("http")
                        && it.value.isNotBlank()
            }
            .map { fixUrl(it.value) }
            .distinct()  // Éviter les doublons

        if (allLinks.isEmpty()) return@mapNotNull null

        // Prendre le premier comme lien principal (pour compatibilité)
        val firstLink = allLinks.first()

        val title =
            if (isVostFr) "Episode $epNum Vostfr 📜 🇬🇧"
            else "Episode $epNum VF 🇫🇷"

        newEpisode(
            loadLinkData(
                embedUrl = firstLink,  // Premier lien pour compatibilité
                isVostFr = isVostFr,
                episodenumber = epNum,
                allLinks = allLinks  // Tous les liens
            ).toJson()
        ) {
            this.name = title
            this.episode = epNum
        }
    }
}



//tagsListperso.add("(Dub\u2335)VF \uD83C\uDDE8\uD83C\uDDF5")
//tagsListperso.add("(Sub\u2335)Vostfr \uD83D\uDCDC \uD83C\uDDEC\uD83C\uDDE7")
override suspend fun load(url: String): LoadResponse {
    val soup = app.get(url).document
    var subEpisodes = listOf<Episode>()
    var dubEpisodes = listOf<Episode>()

    // Titre et description
    val titleElement = soup.selectFirst("h1#s-title")!!
    titleElement.selectFirst("p.desc-text")?.remove()
    val title = titleElement.text()

    val descriptionElement = soup.selectFirst("div.fdesc")!!
    descriptionElement.selectFirst("p.desc-text")?.remove()
    val description = descriptionElement.text()

    // ⚡ Poster conservé exactement comme toi
    val poster = soup.selectFirst("a.short-poster.img-box.with-mask > img")?.attr("data-src")?.takeIf { it.isNotEmpty() }
        ?: soup.selectFirst("a.short-poster.img-box.with-mask > img")?.attr("src")?.takeIf { it.isNotEmpty() }
        ?: soup.selectFirst("div.fposter > dvd-cover > img.thumbnail")?.attr("src")?.takeIf { it.isNotEmpty() }
        ?: soup.selectFirst("a.short-poster.img-box.with-mask > img")?.attr("data-original")?.takeIf { it.isNotEmpty() }
        ?: soup.selectFirst("div.dvd-container > img.dvd-thumbnail")?.attr("src")?.takeIf { it.isNotEmpty() }
        ?: soup.selectFirst("div.dvd-cover > img.thumbnail")?.attr("src")?.takeIf { it.isNotEmpty() }

    // ⚡ Sélection des divs VF et VOSTFR
    val vfDivs = soup.select("#episodes-vf-data > div")
    val vostfrDivs = soup.select("#episodes-vostfr-data > div")

    val isMovie = vfDivs.isEmpty() && vostfrDivs.isEmpty()

    if (isMovie) {
        val yearRegex = Regex("""ate de sortie\: (\d*)""")
        val year = yearRegex.find(soup.text())?.groupValues?.get(1)
        val tags = soup.select("ul.flist-col > li").getOrNull(1)?.select("a")?.mapNotNull { it?.text() }
        return newMovieLoadResponse(title, url, TvType.Movie, loadLinkData(url)) {
            this.posterUrl = poster
            this.year = year?.toIntOrNull()
            this.tags = tags
            this.plot = description
            addTrailer(soup.selectFirst("button#myBtn > a")?.attr("href"))
        }
    } else {
        // ⚡ Priorité VF
        dubEpisodes = vfDivs.takeEpisodeFromDivs(false)
        subEpisodes = vostfrDivs.takeEpisodeFromDivs(true)

        val tagsListperso = mutableListOf<String>()
        if (vfDivs.isNotEmpty()) tagsListperso.add("(Dub\u2335)VF 🇫🇷")
        if (vostfrDivs.isNotEmpty()) tagsListperso.add("(Sub\u2335)Vostfr 📜 🇬🇧")

        val yearRegex = Regex("""Titre .* \/ (\d*)""")
        val year = yearRegex.find(soup.text())?.groupValues?.get(1)

        return newAnimeLoadResponse(title, url, TvType.TvSeries) {
            this.posterUrl = poster
            this.plot = description
            this.year = year?.toInt()
            this.tags = tagsListperso
            addTrailer(soup.selectFirst("button#myBtn > a")?.attr("href"))

            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
        }
    }
}


    fun translate(
        // the website has weird naming of series for episode 2 and 1 and original version content
        episodeNumber: String,
        is_vf_available: Boolean,
    ): String {
        return if (episodeNumber == "1") {
            if (is_vf_available) {  // 1 translate differently if vf is available or not
                "FGHIJK"
            } else {
                "episode033"
            }
        } else {
            "episode" + (episodeNumber.toInt() + 32).toString()
        }
    }

 override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {

    val parsedData = tryParseJson<loadLinkData>(data) ?: return false
    
	
    if (parsedData.episodenumber != null) {
	
		// =======================
		// 🎬 SÉRIES
		// =======================

        // Utiliser TOUS les liens stockés au lieu d'un seul
        if (parsedData.allLinks.isNotEmpty()) {
            var success = false
            parsedData.allLinks.forEach { link ->
                if (loadExtractor(link, mainUrl, subtitleCallback, callback)) {
                    success = true
                }
            }
            return success
        } else {
            // Fallback : utiliser le lien principal (ancien comportement)
            return loadExtractor(fixUrl(parsedData.embedUrl), mainUrl, subtitleCallback, callback)
        }
		
    } else {
        
		
		// =======================
		// 🎥 FILMS 
		// =======================
		
        val url = fixUrl(parsedData.embedUrl)
        val soup = app.get(url).document
        val filmData = soup.selectFirst("div#film-data") ?: return false
        
        val links = mutableListOf<String>()
        val isVostfr = parsedData.isVostFr == true
        
        filmData.attributes().asList().forEach { attr ->
            if (!attr.key.startsWith("data-") || attr.value.isBlank()) return@forEach
            
            val key = attr.key.removePrefix("data-")
            
            // Ignorer les attributs qui ne sont pas des liens de streaming
            if (key in listOf("newsid", "title", "fulllink", "affiche", "affiche2", 
                             "trailer", "tagz", "actors", "ispremium", "vostfr")) {
                return@forEach
            }
            
            // Filtrer selon VF/VOSTFR
            val shouldInclude = if (isVostfr) {
                key.contains("vostfr", ignoreCase = true)
            } else {
                !key.contains("vostfr", ignoreCase = true)
            }
            
            if (shouldInclude && attr.value.startsWith("http")) {
                links.add(attr.value)
            }
        }
        
        if (links.isNotEmpty()) {
            links.distinct().forEach { link ->
                loadExtractor(fixUrl(link), mainUrl, subtitleCallback, callback)
            }
            return true
        }
        
        // Fallback pour les films
        return loadExtractor(url, mainUrl, subtitleCallback, callback)
    }
}


    private fun Element.toSearchResponse(): SearchResponse {
        /////// Recherche et accueil
        val posterUrl = fixUrl(select("a.short-poster.img-box.with-mask > img").attr("data-src") ?: "")
            .takeIf { it.isNotEmpty() }
            ?: fixUrl(select("a.short-poster.img-box.with-mask > img").attr("src") ?: "")
                .takeIf { it.isNotEmpty() }
            ?: fixUrl(selectFirst("div.fposter > dvd-cover > img.thumbnail")?.attr("src") ?: "")
                .takeIf { it.isNotEmpty() }
            ?: fixUrl(select("a.short-poster.img-box.with-mask > img").attr("data-original") ?: "")

        val qualityExtracted = select("span.film-ripz > a").text()
        val type = select("span.mli-eps").text().lowercase()
        val title = select("div.short-title").text()

        val titlesaison = title.replace(" - ", "\n- ")
        val firstlink = select("a.short-poster.img-box.with-mask").attr("href").replace("wvw.", "")

        val link = if (!firstlink.startsWith("https://")) {
            "$mainUrl/$firstlink"
        } else {
            firstlink
        }

        val quality = getQualityFromString(
            when (!qualityExtracted.isNullOrBlank()) {
                qualityExtracted.contains("HDLight") -> "HD"
                qualityExtracted.contains("Bdrip") -> "BlueRay"
                qualityExtracted.contains("DVD") -> "DVD"
                qualityExtracted.contains("CAM") -> "Cam"
                else -> null
            }
        )

        if (!type.contains("eps")) {
            // Correction: 3rd arg must be TvType (not String)
            return newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        } else  // a Serie
        {
            return newAnimeSearchResponse(
                name = titlesaison,
                url = link,
                type = TvType.TvSeries,
            ) {
                this.posterUrl = posterUrl
                addDubStatus(
                    isDub = select("span.film-verz").text().uppercase().contains("VF"),
                    episodes = select("span.mli-eps>i").text().toIntOrNull()
                )
            }
        }
    }

    data class mediaData(
        @JsonProperty("title") var title: String,
        @JsonProperty("url") val url: String,
    )

    override val mainPage = mainPageOf(
        Pair("/page/", "Tout"),
        Pair("/films/page/","Tous les Films"),
        Pair("/s-tv/page/","Toutes les Séries"),
        Pair("/films/animations/page/","Films Animation"),
        Pair("/films/aventures/page/","Films Aventure"),
        Pair("/films/comedies/page/","Films Comédie"),
        Pair("/films/documentaires/page/","Films Documentaire"),
        Pair("/aventure-series-/page/","Series Aventure"),
        Pair("/fantastique-series-/page/","Series Fantastique"),
        Pair("/comedie-serie-/page/","Series Comédie"),
        Pair("/science-fiction-series-/page/","Series Science-Fiction"),
        Pair("/horreur-serie-/page/","Series Horreur"),
        Pair("/documentaire-serie-/page/","Series Documentaire")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        //if (isNotInit) initMainUrl()

        val url = mainUrl + request.data + page
        val document = app.get(url).document
        val movies = document.select("div#dle-content > div.short")

        val home =
            movies.map { article ->  // avec mapnotnull si un élément est null, il sera automatiquement enlevé de la liste
                article.toSearchResponse()
            }
        return newHomePageResponse(request.name, home)
    }
}