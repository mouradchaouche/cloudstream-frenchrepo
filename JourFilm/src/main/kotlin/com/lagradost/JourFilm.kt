package com.lagradost
import com.lagradost.nicehttp.NiceResponse
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element
import org.jsoup.select.Elements


class JourFilm : MainAPI() {
    override var mainUrl = "https://1jour1film1225b.site" 
    override var name = "04- \ud83d\udd25 Un jour Un Film\u269c\ufe0f"
    override val hasQuickSearch = true
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    private val interceptor = CloudflareKiller()

    // Structure JSON des liens VIDEOS
    data class loadLinkData(
        val embedUrl: String,
        val isVostFr: Boolean? = null,
        val episodenumber: Int? = null,
        val allLinks: List<String> = emptyList()
    )

    data class mediaData(
        @JsonProperty("title") var title: String,
        @JsonProperty("url") val url: String,
    )

    suspend fun avoidCloudflare(url: String): NiceResponse {
        if (!app.get(url).isSuccessful) {
            return app.get(url, interceptor = interceptor)
        } else {
            return app.get(url)
        }
    }

    // Fonction pour extraire les épisodes depuis les divs (version corrigée)
    private fun Elements.takeEpisodeFromDivs(isVostFr: Boolean): List<Episode> {
        return this.mapNotNull { div ->
            try {
                val epNum = div.attr("data-ep").toIntOrNull() ?: return@mapNotNull null
                if (epNum <= 0) return@mapNotNull null

                val allLinks = div.attributes()
                    .asList()
                    .filter {
                        it.key.startsWith("data-")
                                && it.key != "data-ep"
                                && it.value.startsWith("http")
                                && it.value.isNotBlank()
                    }
                    .map { fixUrl(it.value) }
                    .distinct()

                if (allLinks.isEmpty()) return@mapNotNull null

                val firstLink = allLinks.first()
                val title = if (isVostFr) "Episode $epNum Vostfr 📜 🇬🇧" else "Episode $epNum VF 🇫🇷"

                newEpisode(
                    loadLinkData(
                        embedUrl = firstLink,
                        isVostFr = isVostFr,
                        episodenumber = epNum,
                        allLinks = allLinks
                    ).toJson()
                ) {
                    this.name = title
                    this.episode = epNum
                }
            } catch (e: Exception) {
                null
            }
        }
    }
	
	// CHARGEMENT DES DÉTAILS - VERSION CORRIGÉE
    override suspend fun load(url: String): LoadResponse {
        val soup = app.get(url).document
        
        // Titre
        val titleElement = soup.selectFirst("h1#s-title")
        titleElement?.selectFirst("p.desc-text")?.remove()
        val title = titleElement?.text() ?: soup.selectFirst("h1")?.text() ?: ""

        // Description
        val descriptionElement = soup.selectFirst("div.fdesc")
        descriptionElement?.selectFirst("p.desc-text")?.remove()
        val description = descriptionElement?.text() ?: ""

        // Poster
			val posterElement = soup.selectFirst("div.poster")
			val poster = posterElement?.let { div ->
				// Chercher dans l'ordre de priorité
				val img = div.selectFirst("img")
				
				val possibleSources = listOfNotNull(
					img?.attr("data-src"),      // Lazy loading
					img?.attr("src"),           // Source normale
					div.selectFirst("noscript img")?.attr("src")  // Fallback noscript
				)
				
				possibleSources.firstOrNull { it.isNotBlank() && it.contains(".") }
					?.let { src ->
						if (src.startsWith("http")) src else fixUrl(src)
					}
			} ?: ""
					

        // Vérifier si c'est un film ou une série
        // Vérifier d'abord la nouvelle structure (saisons)
        val seasonsDivs = soup.select("div#seasons div.se-c")
        
        // Vérifier ensuite l'ancienne structure
        val vfDivs = soup.select("#episodes-vf-data > div")
        val vostfrDivs = soup.select("#episodes-vostfr-data > div")

        val isMovie = seasonsDivs.isEmpty() && vfDivs.isEmpty() && vostfrDivs.isEmpty()

        if (isMovie) {
            // C'est un film
            val yearRegex = Regex("""ate de sortie\:\s*(\d*)""")
            val yearMatch = yearRegex.find(soup.text())
            val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()
            
            val tags = soup.select("ul.flist-col > li").getOrNull(1)?.select("a")?.mapNotNull { it.text() }

            return newMovieLoadResponse(title, url, TvType.Movie, loadLinkData(url).toJson()) {
                this.posterUrl = poster
                this.year = year
                this.tags = tags
                this.plot = description
                addTrailer(soup.selectFirst("button#myBtn > a")?.attr("href"))
            }
        } else {
            // C'est une série
            
            // Méthode 1 : Nouvelle structure avec saisons
            if (seasonsDivs.isNotEmpty()) {
                return handleSeriesWithSeasons(soup, title, description, poster, url)
            }
            
            // Méthode 2 : Ancienne structure (VF/VOSTFR)
            return handleSeriesWithVFVostfr(soup, title, description, poster, url)
        }
    }

    // Gérer les séries avec structure de saisons
    private suspend fun handleSeriesWithSeasons(
        soup: org.jsoup.nodes.Document,
        title: String,
        description: String,
        poster: String?,
        url: String
    ): LoadResponse {
        val seasonsDivs = soup.select("div#seasons div.se-c")
        val episodesBySeason = mutableListOf<Episode>()
        
        // Parcourir chaque saison
        seasonsDivs.forEachIndexed { index, seasonDiv ->
            // Extraire le numéro de saison
            val seasonNumberText = seasonDiv.selectFirst("span.se-t")?.text()?.trim()
            val seasonNumber = seasonNumberText?.toIntOrNull() ?: (index + 1)
            
            // Extraire les épisodes de cette saison
            val episodeItems = seasonDiv.select("ul.episodios > li")
            
            episodeItems.forEach { episodeItem ->
                // Numéro d'épisode (format "5 - 1")
                val episodeNumText = episodeItem.selectFirst("div.numerando")?.text()?.trim()
                val episodeNumber = extractEpisodeNumber(episodeNumText)
                
                // Titre de l'épisode
                val episodeTitle = episodeItem.selectFirst("div.episodiotitle > a")?.text()?.trim() ?: ""
                
                // Lien de l'épisode
                val episodeLink = episodeItem.selectFirst("div.episodiotitle > a")?.attr("href") ?: ""
                
                // Image de l'épisode
                val episodeImage = fixUrl(
                    episodeItem.selectFirst("img[data-src]")?.attr("data-src")
                        ?: episodeItem.selectFirst("img")?.attr("src")
                        ?: ""
                )
                
                if (episodeNumber != null && episodeLink.isNotBlank()) {
                    // Créer l'épisode
                    episodesBySeason.add(
                        newEpisode(loadLinkData(episodeLink).toJson()) {
                            this.name = if (episodeTitle.isNotBlank()) {
                                "S${seasonNumber}E$episodeNumber - $episodeTitle"
                            } else {
                                "S${seasonNumber}E$episodeNumber"
                            }
                            this.season = seasonNumber
                            this.episode = episodeNumber
                            this.posterUrl = episodeImage
                        }
                    )
                }
            }
        }

        // Extraire l'année de manière sécurisée
        val year = extractYearFromPage(soup)

        return newAnimeLoadResponse(title, url, TvType.TvSeries) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            addTrailer(soup.selectFirst("button#myBtn > a")?.attr("href"))

            if (episodesBySeason.isNotEmpty()) {
                addEpisodes(DubStatus.Dubbed, episodesBySeason)
            }
        }
    }

    // Gérer les séries avec ancienne structure VF/VOSTFR
    private suspend fun handleSeriesWithVFVostfr(
        soup: org.jsoup.nodes.Document,
        title: String,
        description: String,
        poster: String?,
        url: String
    ): LoadResponse {
        val vfDivs = soup.select("#episodes-vf-data > div")
        val vostfrDivs = soup.select("#episodes-vostfr-data > div")
        
        val dubEpisodes = vfDivs.takeEpisodeFromDivs(false)
        val subEpisodes = vostfrDivs.takeEpisodeFromDivs(true)

        val tagsListperso = mutableListOf<String>()
        if (vfDivs.isNotEmpty()) tagsListperso.add("(Dub)VF 🇫🇷")
        if (vostfrDivs.isNotEmpty()) tagsListperso.add("(Sub)Vostfr 📜 🇬🇧")

        // Extraire l'année de manière sécurisée
        val year = extractYearFromPage(soup)

        return newAnimeLoadResponse(title, url, TvType.TvSeries) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tagsListperso
            addTrailer(soup.selectFirst("button#myBtn > a")?.attr("href"))

            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
        }
    }

    // Fonction pour extraire le numéro d'épisode de manière sécurisée
    private fun extractEpisodeNumber(numerandoText: String?): Int? {
        if (numerandoText.isNullOrBlank()) return null
        
        try {
            // Format "5 - 1" → on veut "1" (le deuxième nombre)
            val parts = numerandoText.split("-").map { it.trim() }
            return if (parts.size >= 2) {
                parts[1].toIntOrNull()
            } else {
                // Si pas de format saison-épisode, essayer de trouver un nombre
                numerandoText.replace(Regex("[^0-9]"), "").toIntOrNull()
            }
        } catch (e: Exception) {
            return null
        }
    }

    // Fonction pour extraire l'année de manière sécurisée
    private fun extractYearFromPage(soup: org.jsoup.nodes.Document): Int? {
        try {
            // Essayer plusieurs méthodes pour extraire l'année
            val yearRegex = Regex("""Titre .* \/ (\d{4})""")
            val yearMatch = yearRegex.find(soup.text())
            
            if (yearMatch != null) {
                return yearMatch.groupValues[1].toIntOrNull()
            }
            
            // Essayer un autre pattern
            val yearRegex2 = Regex("""(\d{4})""")
            val matches = yearRegex2.findAll(soup.text())
            
            // Prendre le premier nombre à 4 chiffres qui semble être une année
            matches.forEach { match ->
                val year = match.value.toIntOrNull()
                if (year != null && year >= 1900 && year <= 2100) {
                    return year
                }
            }
            
            // Chercher dans les métadonnées
            val metaYear = soup.select("meta[property='og:video:release_date']").attr("content")
            if (metaYear.isNotBlank()) {
                return metaYear.take(4).toIntOrNull()
            }
            
        } catch (e: Exception) {
            // Si erreur, retourner null
        }
        
        return null
    }

    // Fonction pour traiter les ARTICLES (featured et full)
    private fun Element.toArticleSearchResponse(): SearchResponse? {
        // Vérifier le type par la classe
        val isMovie = attr("class").contains("movies")
        val isTvShow = attr("class").contains("tvshows")
        
        // Vérifier si c'est featured
        val id = attr("id")
        val isFeatured = id.contains("post-featured")
        
        // POSTER - plusieurs sources possibles
        val posterUrl = fixUrl(
            select("img[data-src]").attr("data-src")
                ?: select("img.lazyload").attr("data-src")
                ?: select("img").attr("src")
                ?: ""
        )
        
        // TITRE
        val title = select("h3 a").text().trim()
        
        // LIEN
        val link = select("h3 a").attr("href")
        
        // ANNÉE/DATE
        val dateSpan = select("div.data span, div.data.dfeatur span").text().trim()
        val year = extractYearFromDate(dateSpan)
        
        // NOTE
        val rating = select("div.rating").text().trim()
        
        // QUALITÉ (seulement pour les articles normaux, pas featured)
        val quality = if (!isFeatured) {
            select("span.quality").text().trim()
        } else ""
        
        // LABEL "Top Actuel" (seulement pour featured)
        val featuredLabel = if (isFeatured) {
            select("div.featu").text().trim()
        } else ""

        if (title.isBlank() || link.isBlank()) return null
        
        // Construire le titre final avec les informations supplémentaires
        val finalTitle = buildString {
            append(title)
            if (isFeatured && featuredLabel.isNotBlank()) {
                append(" 🔥")
            }
            if (rating.isNotBlank()) {
                append(" ($rating)")
            }
        }
        
        return if (isMovie || !isTvShow) {
            // FILM
            newMovieSearchResponse(finalTitle, link, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
            }
        } else {
            // SÉRIE
            newAnimeSearchResponse(finalTitle, link, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    // Fonction pour traiter les RÉSULTATS DE RECHERCHE
    private fun Element.toSearchResultItem(): SearchResponse? {
        // POSTER (format 150x150)
        val posterUrl = fixUrl(
            select("img[data-src]").attr("data-src")
                ?: select("img.lazyload").attr("data-src")
                ?: select("img").attr("src")
                ?: ""
        )
        
        // TITRE
        val title = select("div.title a").text().trim()
        
        // LIEN
        val link = select("div.title a").attr("href")
        
        // TYPE (vérifier si c'est une série)
        val isTvShow = select("span.tvshows").text().contains("TV", ignoreCase = true)
        
        // ANNÉE
        val yearText = select("span.year").text().trim()
        val year = yearText.toIntOrNull()
        
        // NOTE IMDb
        val ratingText = select("span.rating").text().trim()
        val imdbRating = ratingText.removePrefix("IMDb ").toDoubleOrNull()
        
        // DESCRIPTION
        val description = select("div.contenido p").text().trim()

        if (title.isBlank() || link.isBlank()) return null
        
        // Construire le titre final avec IMDb rating
        val finalTitle = buildString {
            append(title)
            if (imdbRating != null) {
                append(" ⭐$imdbRating")
            }
        }
        
        return if (isTvShow) {
            // SÉRIE
            newAnimeSearchResponse(finalTitle, link, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
            }
        } else {
            // FILM
            newMovieSearchResponse(finalTitle, link, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    // Fonction pour extraire l'année d'une date
    private fun extractYearFromDate(dateText: String): Int? {
        return when {
            // Format "2025"
            dateText.matches(Regex("\\d{4}")) -> dateText.toIntOrNull()
            
            // Format "Nov. 14, 2025"
            dateText.contains(Regex("\\d{4}")) -> {
                val match = Regex("(\\d{4})").find(dateText)
                match?.groupValues?.get(1)?.toIntOrNull()
            }
            
            else -> null
        }
    }

    // RECHERCHE
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        
        // Chercher dans les résultats de recherche (div.result-item)
        val results = document.select("div.result-item")
        
        if (results.isNotEmpty()) {
            return results.mapNotNull { result ->
                try {
                    result.toSearchResultItem()
                } catch (e: Exception) {
                    null
                }
            }
        }
        
        // Fallback: chercher dans les articles si pas de résultats spécifiques
        val fallbackResults = document.select("article.item")
        return fallbackResults.mapNotNull { 
            try {
                it.toArticleSearchResponse()
            } catch (e: Exception) {
                null
            }
        }
    }

    // PAGE PRINCIPALE
    override val mainPage = mainPageOf(
        "" to "Films/Series Populaires",
        "/films/" to "Derniers Films", 
        "/tvshows/" to "Dernières Séries"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()
        
        when (request.name) {
            "Films/Series Populaires" -> {
                // Page d'accueil - section featured
                val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
                val document = app.get(url).document
                
                // Sélectionner les articles dans la section featured
                val featuredArticles = document.select("div.items.featured article.item")
                
                items.addAll(featuredArticles.mapNotNull { article ->
                    try {
                        article.toArticleSearchResponse()
                    } catch (e: Exception) {
                        null
                    }
                })
            }
            
            "Derniers Films" -> {
                // Section des derniers films
                val url = if (page == 1) "$mainUrl/films/" else "$mainUrl/films/page/$page/"
                val document = app.get(url).document
                
                // Sélectionner les articles de films dans div.items.full
                val filmArticles = document.select("div.items.full article.item.movies")
                
                items.addAll(filmArticles.mapNotNull { article ->
                    try {
                        article.toArticleSearchResponse()
                    } catch (e: Exception) {
                        null
                    }
                })
            }
            
            "Dernières Séries" -> {
                // Section des dernières séries
                val url = if (page == 1) "$mainUrl/tvshows/" else "$mainUrl/tvshows/page/$page/"
                val document = app.get(url).document
                
                // Sélectionner les articles de séries dans div.items.full
                val seriesArticles = document.select("div.items.full article.item.tvshows")
                
                items.addAll(seriesArticles.mapNotNull { article ->
                    try {
                        article.toArticleSearchResponse()
                    } catch (e: Exception) {
                        null
                    }
                })
            }
        }
        
        return newHomePageResponse(request.name, items)
    }
    
override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    val parsedData = tryParseJson<loadLinkData>(data) ?: return false
    val contentUrl = fixUrl(parsedData.embedUrl)
    
    val soup = app.get(contentUrl).document
    
    // Chercher les options de lecteur
    val playerOptions = soup.select("#playeroptionsul li.dooplay_player_option[data-post][data-nume][data-type]")
    
    if (playerOptions.isEmpty()) {
        return loadExtractor(contentUrl, mainUrl, subtitleCallback, callback)
    }
    
    // Filtrer par langue si spécifié
    val targetIsVostFr = parsedData.isVostFr == true
    
    // Essayer chaque option
    for (option in playerOptions) {
        val dataNume = option.attr("data-nume")
        if (dataNume == "trailer") continue
        
        val dataPost = option.attr("data-post")
        val dataType = option.attr("data-type")
        val title = option.select("span.title").text().trim()
        
        // Vérifier la langue
        val optionIsVostFr = title.contains("VOSTFR", ignoreCase = true) ||
                            option.select("img[alt*='VOSTFR'], img[alt*='en'], img[alt*='ru']").isNotEmpty()
        
        // Filtrer par langue si spécifié
        if (parsedData.isVostFr != null && optionIsVostFr != targetIsVostFr) {
            continue
        }
        
        // Faire la requête AJAX
        val success = tryAjaxRequest(dataPost, dataNume, dataType, contentUrl, subtitleCallback, callback)
        
        if (success) {
            return true
        }
    }
    
    return false
}

private suspend fun tryAjaxRequest(
    dataPost: String,
    dataNume: String, 
    dataType: String,
    refererUrl: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
    
    // Les paramètres EXACTEMENT comme jQuery les envoie
    val postData = mapOf(
        "action" to "doo_player_ajax",
        "post" to dataPost,
        "nume" to dataNume,
        "type" to dataType
    )
    
    return try {
        // IMPORTANT: Utiliser les mêmes headers que jQuery
        val headers = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to refererUrl,
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
        )
        
        val response = app.post(ajaxUrl, data = postData, headers = headers)
        
        if (response.isSuccessful) {
            val responseText = response.text.trim()
            
            // DEBUG: Afficher ce qu'on reçoit
            println("JOURFILM AJAX [nume=$dataNume]: $responseText")
            
            // Structure pour parser la réponse JSON
            data class AjaxResponse(
                val embed_url: String? = null,
                val type: String? = null,
                val url: String? = null
            )
            
            // Essayer de parser comme JSON
            val jsonResponse = tryParseJson<AjaxResponse>(responseText)
            
            // Prendre l'URL depuis JSON
            val videoUrl = when {
                jsonResponse?.embed_url != null -> {
                    println("JOURFILM: ✅ Embed URL trouvé: ${jsonResponse.embed_url}")
                    jsonResponse.embed_url
                }
                jsonResponse?.url != null -> {
                    println("JOURFILM: ✅ URL directe trouvée: ${jsonResponse.url}")
                    jsonResponse.url
                }
                responseText.startsWith("http") && responseText.length < 500 -> {
                    println("JOURFILM: ✅ Réponse est une URL: $responseText")
                    responseText
                }
                else -> {
                    println("JOURFILM: ❌ Aucune URL trouvée dans la réponse")
                    null
                }
            }
            
            if (videoUrl != null && videoUrl.startsWith("http")) {
                println("JOURFILM: 🔄 Envoi à loadExtractor: $videoUrl")
                return loadExtractor(fixUrl(videoUrl), mainUrl, subtitleCallback, callback)
            }
        } else {
            println("JOURFILM: ❌ Réponse AJAX échouée: ${response.code}")
        }
        false
    } catch (e: Exception) {
        println("JOURFILM: 💥 Exception AJAX: ${e.message}")
        false
    }
}


}