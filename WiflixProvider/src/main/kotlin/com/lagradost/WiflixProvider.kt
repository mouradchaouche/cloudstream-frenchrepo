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

class WiflixProvider : MainAPI() {

    override var mainUrl = "https://flemmix.bond" //"https://wiflix.al" // https://flemmix.one
    override var name = "01- \u2b50 Wiflix \u2b50"
    override val hasQuickSearch = false // recherche rapide (optionel, pas vraimet utile)
    override val hasMainPage = true // page d'accueil (optionel mais encoragé)
    override var lang = "fr" // fournisseur est en francais
    override val supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries) // series, films
    private val interceptor = CloudflareKiller()
    private var isNotInit = true

    suspend fun initMainUrl() {
    try {
        val document = avoidCloudflare(mainUrl).document
        val newMainUrl = document.select("link[rel*=\"canonical\"]").attr("href")
        if (!newMainUrl.isNullOrBlank() && newMainUrl.contains("wiflix")) {
            mainUrl = newMainUrl
        } else {
            // Récupérer l'URL depuis le fichier JSON
            val mediaDataList = app.get("https://amsc.duckdns.org/cloudstream/repo/urls.json")
                .parsed<ArrayList<mediaData>>()

            mediaDataList.forEach { mediaData ->
                if (mediaData.title.equals("Wiflix", ignoreCase = true)) {
                    mainUrl = mediaData.url
                    return@forEach
                }
            }
        }
    } catch (e: Exception) {
        // En cas d'erreur lors de la récupération de l'URL canonique ou du fichier JSON, gérer l'exception
        // Vous pouvez choisir de récupérer l'URL depuis le fichier JSON de secours ou prendre d'autres mesures
        val mediaDataList = app.get("https://amsc.duckdns.org/cloudstream/repo/urls.json")
            .parsed<ArrayList<mediaData>>()

        mediaDataList.forEach { mediaData ->
            if (mediaData.title.equals("Wiflix", ignoreCase = true)) {
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


    /**
    Cherche le site pour un titre spécifique

    La recherche retourne une SearchResponse, qui peut être des classes suivants: AnimeSearchResponse, MovieSearchResponse, TorrentSearchResponse, TvSeriesSearchResponse
    Chaque classes nécessite des données différentes, mais a en commun le nom, le poster et l'url
     **/
    override suspend fun search(query: String): List<SearchResponse> {
        val link =
            "$mainUrl/index.php?do=search&subaction=search&search_start=0&full_search=1&result_from=1&story=$query&titleonly=3&searchuser=&replyless=0&replylimit=0&searchdate=0&beforeafter=after&sortby=date&resorder=desc&showposts=0&catlist%5B%5D=0" // search'
        val document =
            app.post(link).document // app.get() permet de télécharger la page html avec une requete HTTP (get)
        val results = document.select("div#dle-content > div.clearfix")

        val allresultshome =
            results.mapNotNull { article ->  // avec mapnotnull si un élément est null, il sera automatiquement enlevé de la liste
                article.toSearchResponse()
            }
        return allresultshome
    }

    /**
     * charge la page d'informations, il ya toutes les donées, les épisodes, le résumé etc ...
     * Il faut retourner soit: AnimeLoadResponse, MovieLoadResponse, TorrentLoadResponse, TvSeriesLoadResponse.
     */
    data class EpisodeData(
        @JsonProperty("url") val url: String,
        @JsonProperty("episodeNumber") val episodeNumber: String,
    )

    private fun Elements.takeEpisode(
        url: String,
        posterUrl: String?,
        duborSub: String?
    ): ArrayList<Episode> {

        val episodes = ArrayList<Episode>()
        this.select("ul.eplist > li").forEach {

            val strEpisodeN =
                Regex("""pisode[\s]+(\d+)""").find(it.text())?.groupValues?.get(1).toString()
            val link =
                EpisodeData(
                    url,
                    strEpisodeN,
                ).toJson()


            episodes.add(
                Episode(
                    link + if (duborSub == "vostfr") {
                        "*$duborSub*"
                    } else {
                        "VF"
                    },
                    name = "Episode en " + duborSub,
                    episode = strEpisodeN.toIntOrNull(),
                    posterUrl = posterUrl
                )
            )
        }

        return episodes
    }

    override suspend fun load(url: String): LoadResponse {
        val document = avoidCloudflare(url).document //
        // url est le lien retourné par la fonction search (la variable href) ou la fonction getMainPage
        var subEpisodes = ArrayList<Episode>()
        var dubEpisodes = ArrayList<Episode>()
        val mediaType: TvType
        val episodeFrfound =
            document.select("div.blocfr")
        val episodeVostfrfound =
            document.select("div.blocvostfr")	
			
        val title =
            document.select("h1[itemprop]").text()
        val posterUrl =
            document.select("img#posterimg").attr("src")
        val yearRegex = Regex("""ate de sortie\: (\d*)""")
        val year = yearRegex.find(document.text())?.groupValues?.get(1)


        //val tags = document.select("[itemprop=genre] > a")
         //   .map { it.text() } // séléctione tous les tags et les ajoutes à une liste
		 
		 
		 
		 val tags = document.select("[itemprop=genre] > a")
			.map {
				it.text().replace(Regex("(?i)VF"), "Lang.(Dub\u2335)VF \uD83C\uDDE8\uD83C\uDDF5")
				.replace(Regex("(?i)vostfr"), "Lang.(Sub\u2335)Vostfr \uD83D\uDCDC \uD83C\uDDEC\uD83C\uDDE7")
				}
				
				
				/////Retirer Series
				// val filteredTags = tags.filterNot { it.contains("Series", ignoreCase = true) }
				
			// Restructuration des tags en ajoutant "Genre: " au début
			// val restructuredTags = filteredTags.map { "Genre: $it" }
		
		
		//val tags = document.select("[itemprop=genre] > a")
		//	.map { element ->
		//	var text = element.text()
		//	if (!text.contains("Lang.")) {
		//		text = text.replace(Regex("(?i)VF"), "Lang.(Dub\u2335)VF \uD83C\uDDE8\uD83C\uDDF5")
		//	} else {
		//		text = text.replace(Regex("(?i)VF"), "(Dub\u2335)VF \uD83C\uDDE8\uD83C\uDDF5")
		//	}
		//	if (!text.contains("Lang.")) {
		//		text = text.replace(Regex("(?i)vostfr"), "Lang.(Sub\u2335)Vostfr \uD83D\uDCDC \uD83C\uDDEC\uD83C\uDDE7")
		//	} else {
		//		text = text.replace(Regex("(?i)vostfr"), "(Sub\u2335)Vostfr \uD83D\uDCDC \uD83C\uDDEC\uDDE7")
		//	}
		//	text
		//}.toMutableList()
		
		
		////////////////////////////// Ajout Manuel et xploitation des tags
		//			// Ajout de tags manuellement
		// 			val customTags = listOf(
		//				"Action",
		//				"Drama",
		//				"Science Fiction"
		//			)
		//
		//			// Ajout des tags personnalisés à la liste existante
		//			tags.addAll(customTags)
		//
		//			// Utilisation des tags
		//			tags.forEach { tag ->
		//				println(tag)
		//			}
		////////////////////////////////////////////////////////////////////////
		
		
        mediaType = TvType.TvSeries
        if (episodeFrfound.text().lowercase().contains("episode")) {
            val duborSub = "VF \uD83C\uDDE8\uD83C\uDDF5"
            dubEpisodes = episodeFrfound.takeEpisode(url, fixUrl(posterUrl), duborSub)
        }
        if (episodeVostfrfound.text().lowercase().contains("episode")) {
            val duborSub = "vostfr \uD83D\uDCDC \uD83C\uDDEC\uD83C\uDDE7"
            subEpisodes = episodeVostfrfound.takeEpisode(url, fixUrl(posterUrl), duborSub)
        }
        ///////////////////////////////////////////
        ///////////////////////////////////////////
        var type_rec: TvType
        val recommendations =
            document.select("div.clearfixme > div > div").mapNotNull { element ->
                val recTitle =
                    element.select("a").text() ?: return@mapNotNull null
                val image = element.select("a >img").attr("src")
                val recUrl = element.select("a").attr("href")
                type_rec = TvType.TvSeries
                if (recUrl.contains("film")) type_rec = TvType.Movie

                if (type_rec == TvType.TvSeries) {
                    TvSeriesSearchResponse(
                        recTitle,
                        recUrl,
                        this.name,
                        TvType.TvSeries,
                        image?.let { fixUrl(it) },
                        )
                } else
                    MovieSearchResponse(
                        recTitle,
                        recUrl,
                        this.name,
                        TvType.Movie,
                        image?.let { fixUrl(it) },
                    )
            }

        val comingSoon = url.contains("films-prochainement")
        if (subEpisodes.isEmpty() && dubEpisodes.isEmpty()) {
			val fullDescription = document.selectFirst("div.screenshots-full")?.text()
			val description = fullDescription?.substringAfterLast(":")

            //val description = document.selectFirst("div.screenshots-full")?.text()
                ?.replace("(.* .ynopsis)".toRegex(), "")
            return newMovieLoadResponse(
                name = title,
                url = url,
                type = TvType.Movie,
                dataUrl = url + if (document.select("span[itemprop*=\"inLanguage\"]").text()
                        .contains("vostfr", true)
                ) {
                    "*vostfr*"
                } else {
                    ""
                }
            ) {
                this.posterUrl = fixUrl(posterUrl)
                this.plot = description
                this.recommendations = recommendations
                this.year = year?.toIntOrNull()
                this.comingSoon = comingSoon
                this.tags = tags
            }
        } else {
			// Extraction du texte de l'élément
			val fullDescription = document.selectFirst("span[itemprop=description]")?.text()

			// Extraction de la partie du texte après ":"
			val description = fullDescription?.substringAfterLast(":")
			
            //val description = document.selectFirst("span[itemprop=description]")?.text()
            return newAnimeLoadResponse(
                title,
                url,
                mediaType,
            ) {
                this.posterUrl = fixUrl(posterUrl)
                this.plot = description
                this.recommendations = recommendations
                this.year = year?.toIntOrNull()
                this.comingSoon = comingSoon
                this.tags = tags
                if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
                if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
            }
        }
    }

// récupere les liens .mp4 ou m3u8 directement à partir du paramètre data généré avec la fonction load()
override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit) : Boolean  {
        var isvostfr = false
        val trueUrl: String
        val parsedInfo = if (data.takeLast(8) == "*vostfr*") 
        {
            isvostfr = true
            trueUrl = data.dropLast(8)
            tryParseJson<EpisodeData>(data.dropLast(8))
        } 
        else 
        {
            trueUrl = data
            tryParseJson<EpisodeData>(data)
        }

        val url = parsedInfo?.url ?: trueUrl
        val numeroEpisode = parsedInfo?.episodeNumber

        // Charge le document HTML en évitant Cloudflare
        val document = avoidCloudflare(url).document

        var flag = "\uD83C\uDDE8\uD83C\uDDF5" // Drapeau par défaut pour les épisodes doublés
        var cssCodeForPlayer: String

        // Vérifie s'il s'agit d'une série en vérifiant si le numéro de l'épisode est disponible
        if (numeroEpisode != null) 
        {
            // Logique pour les séries
            cssCodeForPlayer = if (!isvostfr) {
                "div.ep${numeroEpisode}vf > a"
            } else {
                "div.ep${numeroEpisode}vs > a"
            }

        } //// EN OF if (numeroEpisode != null)
        else // numeroEpisode = null
        {
            // Logique pour les films
            cssCodeForPlayer = "div.tabs-sel.linkstab > div.tabs-sel.linkstab > a"
        }  //End of else (numeroEpisode = null)
		
		// Met à jour le drapeau si c'est un épisode sous-titré
            if (cssCodeForPlayer.contains("vs") || isvostfr) {
                flag = " \uD83D\uDCDC \uD83C\uDDEC\uD83C\uDDE7"
            }

            document.select(cssCodeForPlayer).forEach { player ->
            //    var playerUrl = player.attr("onclick").substringAfter("loadVideo('").substringBefore("')")

                var playerUrl = player.attr("onclick")
                .substringAfter("loadVideo(&#39;")
                .substringBefore("&#39;")
                .substringAfter("loadVideo('")
                .substringBefore("')")
            
                if (!playerUrl.isBlank()) 
				{
                    if (playerUrl.contains("dood") || playerUrl.contains("d00")) {
                        playerUrl = playerUrl.replace("doodstream.com", "dood.wf")
                    }
                    loadExtractor(
                        httpsify(playerUrl),
                        playerUrl,
                        subtitleCallback
                    ) { link ->
                        callback.invoke(
                            ExtractorLink(
                                link.source,
                                link.name + flag,
                                link.url,
                                link.referer,
                                getQualityFromName("HD"), // Vous pouvez adapter la qualité ici
                                link.isM3u8,
                                link.headers,
                                link.extractorData
                            )
                        )
                    }
                } else 
				{
					cssCodeForPlayer = "div.tabs-sel.linkstab > div.tabs-sel.linkstab > a"
					document.select(cssCodeForPlayer).forEach { player ->
            //    var playerUrl = player.attr("onclick").substringAfter("loadVideo('").substringBefore("')")

                var playerUrl = player.attr("onclick")
                .substringAfter("loadVideo(&#39;")
                .substringBefore("&#39;")
                .substringAfter("loadVideo('")
                .substringBefore("')")
            
                if (!playerUrl.isBlank()) 
				{
                    if (playerUrl.contains("dood") || playerUrl.contains("d00")) {
                        playerUrl = playerUrl.replace("doodstream.com", "dood.wf")
                    }
                    loadExtractor(
                        httpsify(playerUrl),
                        playerUrl,
                        subtitleCallback
                    ) { link ->
                        callback.invoke(
                            ExtractorLink(
                                link.source,
                                link.name + flag,
                                link.url,
                                link.referer,
                                getQualityFromName("HD"), // Vous pouvez adapter la qualité ici
                                link.isM3u8,
                                link.headers,
                                link.extractorData
                            )
                        )
                    }
                }
				}
				}				
            }
        return true
}

    private fun Element.toSearchResponse(): SearchResponse {

        val posterUrl = fixUrl(select("div.img-box > img").attr("src"))
        val qualityExtracted = select("div.nbloc1-2 >span").text()
        val type = select("div.nbloc3").text().lowercase()
        val titlefirst = select("a.nowrap").text()
		val seasonAndLanguage = select("span.block-sai").text()
		val title = "$titlefirst\n$seasonAndLanguage"
		//block-sai
        val link = select("a.nowrap").attr("href")
        val quality = getQualityFromString(
            when (!qualityExtracted.isNullOrBlank()) {
                qualityExtracted.contains("HDLight") -> "HD"
                qualityExtracted.contains("Bdrip") -> "BlueRay"
                qualityExtracted.contains("DVD") -> "DVD"
                qualityExtracted.contains("CAM") -> "Cam"

                else -> null
            }
        )
        if (type.contains("film")) {
            return newAnimeSearchResponse(
                name = title,
                url = link,
                type = TvType.Movie,

                ) {
                this.dubStatus = if (select("span.nbloc1").text().contains("vostfr", true)) {
                    EnumSet.of(DubStatus.Subbed)
                } else {
                    EnumSet.of(DubStatus.Dubbed)
                }
                this.posterUrl = posterUrl
                this.quality = quality

            }


        } else  // an Serie
        {

            return newAnimeSearchResponse(
                name = title,
                url = link,
                type = TvType.TvSeries,

                ) {
                this.posterUrl = posterUrl
                this.quality = quality
                addDubStatus(
                    isDub = !select("span.block-sai").text().uppercase().contains("VOSTFR"),
                    episodes = Regex("""pisode[\s]+(\d+)""").find(select("div.block-ep").text())?.groupValues?.get(
                        1
                    )?.toIntOrNull()
                )
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

    data class mediaData(
        @JsonProperty("title") var title: String,
        @JsonProperty("url") val url: String,
    )


    override val mainPage = mainPageOf(
        Pair("/film-en-streaming/page/", "Top Films cette année"),
        Pair("/serie-en-streaming/page/", "Top Séries cette année"),
        Pair("/saison-complete/page/", "Les saisons complètes"),
        Pair("/film-ancien/page/", "Anciens Films"),
		//Pair("/films-prochainement/page/", "Film Prochainement en Streaming"),
		
		Pair("/film-en-streaming/historique/page/", "Historique"),
		Pair("/film-en-streaming/famille/page/", "Famille"),
		//Pair("/film-en-streaming/action/page/", "Action"),
		//Pair("/film-en-streaming/thriller/page/", "Thriller"),
		Pair("/film-en-streaming/comedie/page/", "Comédie"),
		Pair("/film-en-streaming/drame/page/", "Drame"),
		Pair("/film-en-streaming/fantastique/page/", "Fantastique"),
		Pair("/film-en-streaming/science-fiction/page/", "Science Fiction"),
		Pair("/film-en-streaming/aventure/page/", "Aventure"),
		//Pair("/film-en-streaming/horreur/page/", "Epouvante-horreur"),
		//Pair("/film-en-streaming/policier/page/", "Policier"),
		Pair("/film-en-streaming/animation/page/", "Animation")

    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
    // Initialiser l'URL principale si ce n'est pas déjà fait
    if (isNotInit) initMainUrl()

    // Construire l'URL pour la requête actuelle
    val url = mainUrl + request.data + page

    // Obtenir le document HTML en évitant Cloudflare
    val document = avoidCloudflare(url).document

    // Sélectionner les éléments HTML avec les deux sélecteurs
    val movies = document.select("div#dle-content > div.clearfix")

    // Transformer les éléments sélectionnés en objets de réponse
    val home = movies.mapNotNull { element ->
        element.toSearchResponse()
    }

    // Retourner une nouvelle réponse de page d'accueil
    return newHomePageResponse(request.name, home)
}
}
