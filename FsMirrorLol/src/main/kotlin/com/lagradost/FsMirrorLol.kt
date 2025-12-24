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

    private fun Element.takeEpisode(
        url: String,
    ): List<Episode> {
        return this.select("a").map { a ->
            val epNum =
                Regex("""pisode[\s]+(\d+)""").find(a.text().lowercase())?.groupValues?.get(1)
                    ?.toIntOrNull()
            val epTitle = if (a.text().contains("Episode")) {
                val type = if ("honey" in a.attr("id")) {
                    "VF \uD83C\uDDE8\uD83C\uDDF5"
                } else {
                    "Vostfr \uD83D\uDCDC \uD83C\uDDEC\uD83C\uDDE7"
                }
                "Episode $type"
            } else {
                a.text()
            }

            // Utilisation de la factory newEpisode (remplacement de l'ancien Episode(...))
            newEpisode(
                loadLinkData(
                    fixUrl(url),
                    epTitle.contains("Vostfr"),
                    epNum,
                ).toJson()
            ) {
                this.name = epTitle
                this.episode = epNum
                this.posterUrl = a.selectFirst("div.fposter > dvd-cover > img.thumbnail")?.attr("src")
            }
        }
    }

    data class loadLinkData(
        val embedUrl: String,
        val isVostFr: Boolean? = null,
        val episodenumber: Int? = null,
    )

    override suspend fun load(url: String): LoadResponse {
        //// Affichage d'un element
        val soup = app.get(url).document
        var subEpisodes = listOf<Episode>()
        var dubEpisodes = listOf<Episode>()

        //Sélection du titre
        var titleElement = soup.selectFirst("h1#s-title")!!

        //Suppression de la balise <p class="desc-text">
        titleElement.selectFirst("p.desc-text")?.remove()

        //Récupération du texte du titre après suppression
        val title = titleElement.text().toString()

        //Ancien Val title
        //val title = soup.selectFirst("h1#s-title")!!.text().toString()
        //val isMovie = !url.contains("/serie/", ignoreCase = true)
        val isMovie = soup.select("div.elink").isEmpty()

        val descriptionElement = soup.selectFirst("div.fdesc")!!

        // Suppression de la balise <p> masquée
        descriptionElement.selectFirst("p.desc-text")?.remove()

        // Extraction du texte restant
        val description = descriptionElement.text().toString()

        val poster = soup.selectFirst("a.short-poster.img-box.with-mask > img")?.attr("data-src")?.takeIf { it.isNotEmpty() }
            ?: soup.selectFirst("a.short-poster.img-box.with-mask > img")?.attr("src")?.takeIf { it.isNotEmpty() }
            ?: soup.selectFirst("div.fposter > dvd-cover > img.thumbnail")?.attr("src")?.takeIf { it.isNotEmpty() }
            ?: soup.selectFirst("a.short-poster.img-box.with-mask > img")?.attr("data-original")?.takeIf { it.isNotEmpty() }
            ?: soup.selectFirst("div.dvd-container > img.dvd-thumbnail")?.attr("src")?.takeIf { it.isNotEmpty() }
            ?: soup.selectFirst("div.dvd-cover > img.thumbnail")?.attr("src")?.takeIf { it.isNotEmpty() }

        val listEpisode = soup.select("div.elink")
        val tags = soup.select("ul.flist-col > li").getOrNull(1)
        val posterMovie = soup.selectFirst("a.short-poster.img-box.with-mask > img.thumbnail")?.attr("src")
        if (isMovie) {
            val yearRegex = Regex("""ate de sortie\: (\d*)""")
            val year = yearRegex.find(soup.text())?.groupValues?.get(1)
            val tagsList = tags?.select("a")
                ?.mapNotNull {   // all the tags like action, thriller ...; unused variable
                    it?.text()
                }
            return newMovieLoadResponse(title, url, TvType.Movie, loadLinkData(url)) {
                this.posterUrl = poster
                this.year = year?.toIntOrNull()
                this.tags = tagsList
                this.plot = description
                //this.rating = rating
                addTrailer(soup.selectFirst("button#myBtn > a")?.attr("href"))
            }
        } else {
            val vfContainer = listEpisode.firstOrNull { it.selectFirst("a")?.attr("id")?.startsWith("honey") ?: false }
            val vostfrContainer =  listEpisode.firstOrNull { it.selectFirst("a")?.attr("id")?.startsWith("yoyo") ?: false }

            subEpisodes = vostfrContainer?.takeEpisode(url) ?: emptyList()
            dubEpisodes = vfContainer?.takeEpisode(url) ?: emptyList()

            val tagsListperso = mutableListOf<String>()

            if (listEpisode.any { it.selectFirst("a")?.attr("id")?.startsWith("honey") == true }) {
                tagsListperso.add("(Dub\u2335)VF \uD83C\uDDE8\uD83C\uDDF5")
            }
            if (listEpisode.any { it.selectFirst("a")?.attr("id")?.startsWith("yoyo") == true }) {
                tagsListperso.add("(Sub\u2335)Vostfr \uD83D\uDCDC \uD83C\uDDEC\uD83C\uDDE7")
            }

            val yearRegex = Regex("""Titre .* \/ (\d*)""")
            val year = yearRegex.find(soup.text())?.groupValues?.get(1)

            return newAnimeLoadResponse(
                title,
                url,
                TvType.TvSeries,
            ) {
                this.posterUrl = poster
                this.plot = description
                this.year = year?.toInt()
                this.tags = tagsListperso
                addTrailer(soup.selectFirst("button#myBtn > a")?.attr("href"))
                if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
                if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
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

    override suspend fun loadLinks( // TODO FIX *Garbage* data transmission betwenn function
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val parsedData =  tryParseJson<loadLinkData>(data)
        val url = parsedData?.embedUrl ?: return false
        val servers =
            if (parsedData.episodenumber != null) // It's a serie:
            {
                val isvostfr = parsedData.isVostFr == true
                val wantedEpisode =
                    if (parsedData.episodenumber.toString() == "2") { // the episode number 2 has id of ABCDE, don't ask any question
                        "ABCDE"
                    } else {
                        "episode" + parsedData.episodenumber.toString()
                    }

                val soup = app.get(fixUrl(url)).document
                val div =
                    if (wantedEpisode == "episode1") {
                        "> div.tabs-sel "  // this element is added when the wanted episode is one (the place changes in the document)
                    } else {
                        ""
                    }
                val serversvf =// French version servers
                    soup.select("div#$wantedEpisode > div.selink > ul.btnss $div> li")
                        .mapNotNull { li ->  // list of all french version servers
                            val serverUrl = fixUrl(li.selectFirst("a")!!.attr("href"))
                            if (serverUrl.isNotBlank()) {
                                if (li.text().replace("&nbsp;", "").replace(" ", "").isNotBlank()) {
                                    Pair(li.text().replace(" ", ""), fixUrl(serverUrl))
                                } else {
                                    null
                                }
                            } else {
                                null
                            }
                        }

                val translated = translate(parsedData.episodenumber.toString(), serversvf.isNotEmpty())
                val serversvo =  // Original version servers
                    soup.select("div#$translated > div.selink > ul.btnss $div> li")
                        .mapNotNull { li ->
                            val serverUrl = fixUrlNull(li.selectFirst("a")?.attr("href"))
                            if (!serverUrl.isNullOrEmpty()) {
                                if (li.text().replace("&nbsp;", "").isNotBlank()) {
                                    Pair(li.text().replace(" ", ""), fixUrl(serverUrl))
                                } else {
                                    null
                                }
                            } else {
                                null
                            }
                        }
                if (isvostfr) {
                    serversvo
                } else {
                    serversvf
                }
            } else {  // it's a movie
                val movieServers = app.get(fixUrl(url)).document.select("div#player-options > button.player-option").mapNotNull { button ->
                    val playerName = button.attr("data-player") // Nom du player (ex: Dood.Stream, Filmoon, etc.)
                    val defaultUrl = button.attr("data-url-default") // URL par défaut

                    // Extraire les versions disponibles
                    val versions = button.select("div.version-dropdown > div.version-option").mapNotNull { version ->
                        val versionName = version.attr("data-version") // Nom de la version (ex: VOSTFR, VFQ, VFF)
                        val versionUrl = version.attr("data-url") // URL de la version
                        Pair("$playerName - $versionName", fixUrl(versionUrl))
                    }

                    // Ajouter l'URL par défaut comme une option supplémentaire
                    if (defaultUrl.isNotBlank()) {
                        versions + Pair("$playerName - Default", fixUrl(defaultUrl))
                    } else {
                        versions
                    }
                }.flatten()

                movieServers
            }
        servers.apmap {
            val urlplayer = it.second

            val playerUrl = if (urlplayer.contains("opsktp.com") || urlplayer.contains("flixeo.xyz")) {
                val header = app.get(
                    "https" + it.second.split("https")[1],
                    allowRedirects = false
                ).headers
                header["location"].toString()
            } else {
                urlplayer
            }.replace("https://doodstream.com", "https://dood.yt")
            loadExtractor(playerUrl, mainUrl, subtitleCallback, callback)
        }

        return true
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
