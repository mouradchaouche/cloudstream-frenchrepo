package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

class JourFilm : MainAPI() {
    // Mise à jour de l'URL selon ton dernier exemple
    override var mainUrl = "https://1jour1film0426c.site" 
    override var name = "05- 🔥 Un jour Un Film⚜️"
    override val hasQuickSearch = true
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    
    private val interceptor = CloudflareKiller()
    
    private val standardHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept-Language" to "fr-FR,fr;q=0.9"
    )

    data class loadLinkData(
        val embedUrl: String,
        val isVostFr: Boolean? = null,
        val episodenumber: Int? = null,
        val allLinks: List<String> = emptyList()
    )

    override val mainPage = mainPageOf(
        "/catalogue-films/" to "FILMS",
        "/catalogue-series/" to "SÉRIES", 
        "/genre/drame/" to "DRAME"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl${request.data}" else "$mainUrl${request.data}page/$page/"
        val response = app.get(url, headers = standardHeaders, interceptor = interceptor)
        val document = response.document
        
        // Sélection large pour attraper toutes les cartes
        val items = document.select("a.j1f-card, a.j1f-bdslider-item").mapNotNull {
            it.toArticleSearchResponse()
        }
        
        return newHomePageResponse(request.name, items)
    }

    // --- LOGIQUE MISE À JOUR POUR LES NOUVELLES BALISES ---
    private fun Element.toArticleSearchResponse(): SearchResponse? {
        val link = this.attr("href") ?: ""
        if (link.isBlank()) return null

        // Extraction du titre via .card-title ou .j1f-card__title
        val title = this.selectFirst(".card-title, .j1f-card__title, .j1f-bdslider-title")?.text()?.trim() 
                    ?: this.selectFirst("h3")?.text()?.trim()
                    ?: return null

        // Image
        val img = this.selectFirst("img")
        val posterUrl = fixUrl(img?.attr("data-src")?.ifBlank { img.attr("src") } ?: "")

        // Type (Série ou Film)
        val badgeType = this.selectFirst(".card-badge-type, .j1f-card__type")?.text()?.lowercase() ?: ""
        val isTv = badgeType.contains("serie") || link.contains("/tvshows/") || link.contains("-serie-")

        // Année
        val year = this.selectFirst(".card-year, .j1f-card__year")?.text()?.trim()?.toIntOrNull()

        return if (isTv) {
            newAnimeSearchResponse(title, link, TvType.TvSeries) { 
                this.posterUrl = posterUrl
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, link, TvType.Movie) { 
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", headers = standardHeaders).document
        return document.select("a.j1f-card, a.j1f-bdslider-item, .result-item a").mapNotNull {
            it.toArticleSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val soup = app.get(url, headers = standardHeaders).document
        
        val title = soup.selectFirst("h1#s-title")?.text()?.substringBefore("Partager")?.trim() 
                    ?: soup.selectFirst("h1")?.text()?.trim() ?: ""
        val plot = soup.selectFirst("div.fdesc, .wp-content p, .description")?.text() ?: ""
        val poster = fixUrl(soup.selectFirst("div.poster img")?.attr("data-src") 
                     ?: soup.selectFirst("div.poster img")?.attr("src") ?: "")
        
        val year = soup.selectFirst(".date, .card-year, .j1f-card__year")?.text()?.trim()?.toIntOrNull()

        val vfDivs = soup.select("#episodes-vf-data > div")
        val vostfrDivs = soup.select("#episodes-vostfr-data > div")
        val seasonsDivs = soup.select("div#seasons div.se-c")

        if (seasonsDivs.isEmpty() && vfDivs.isEmpty() && vostfrDivs.isEmpty()) {
            return newMovieLoadResponse(title, url, TvType.Movie, loadLinkData(url).toJson()) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            val list = mutableListOf<Episode>()
            seasonsDivs.forEach { season ->
                val sNum = season.selectFirst("span.se-t")?.text()?.toIntOrNull() ?: 1
                season.select("ul.episodios > li").forEach { ep ->
                    val eNum = ep.selectFirst("div.numerando")?.text()?.split("-")?.last()?.trim()?.toIntOrNull()
                    val eLink = ep.selectFirst("div.episodiotitle > a")?.attr("href") ?: ""
                    if (eNum != null) {
                        list.add(newEpisode(loadLinkData(eLink).toJson()) {
                            this.season = sNum
                            this.episode = eNum
                            this.name = ep.selectFirst("div.episodiotitle > a")?.text()?.trim()
                        })
                    }
                }
            }

            return newAnimeLoadResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                if (list.isNotEmpty()) addEpisodes(DubStatus.Dubbed, list)
                if (vfDivs.isNotEmpty()) addEpisodes(DubStatus.Dubbed, vfDivs.takeEpisodeFromDivs(false))
                if (vostfrDivs.isNotEmpty()) addEpisodes(DubStatus.Subbed, vostfrDivs.takeEpisodeFromDivs(true))
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val parsedData = tryParseJson<loadLinkData>(data) ?: return false
        if (parsedData.allLinks.isNotEmpty()) {
            parsedData.allLinks.forEach { loadExtractor(cleanVideoUrl(it), mainUrl, subtitleCallback, callback) }
            return true
        }
        val contentUrl = fixUrl(parsedData.embedUrl)
        val soup = app.get(contentUrl, headers = standardHeaders, interceptor = interceptor).document
        val options = soup.select("ul#playeroptionsul li.dooplay_player_option")
        var found = false
        for (opt in options) {
            val nume = opt.attr("data-nume")
            if (nume == "trailer") continue
            if (tryAjaxRequest(opt.attr("data-post"), nume, opt.attr("data-type"), contentUrl, subtitleCallback, callback)) {
                found = true
            }
        }
        return found
    }

    private suspend fun tryAjaxRequest(post: String, nume: String, type: String, ref: String, sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit): Boolean {
        val res = app.post("$mainUrl/wp-admin/admin-ajax.php", 
            data = mapOf("action" to "doo_player_ajax", "post" to post, "nume" to nume, "type" to type),
            headers = standardHeaders.plus("X-Requested-With" to "XMLHttpRequest").plus("Referer" to ref)
        )
        if (res.isSuccessful) {
            val raw = Regex("""["'](?:embed_url|url|embed_frame_url)["']\s*:\s*["']([^"']+)["']""").find(res.text)?.groupValues?.get(1) ?: ""
            if (raw.isNotBlank() && raw != "0") {
                return loadExtractor(cleanVideoUrl(raw.replace("\\/", "/")), ref, sub, cb)
            }
        }
        return false
    }

    private fun Elements.takeEpisodeFromDivs(vost: Boolean): List<Episode> {
        return this.mapNotNull { div ->
            val num = div.attr("data-ep").toIntOrNull() ?: return@mapNotNull null
            val links = div.attributes().filter { it.key.startsWith("data-") && it.value.startsWith("http") }.map { it.value }
            if (links.isEmpty()) return@mapNotNull null
            newEpisode(loadLinkData(links.first(), vost, num, links).toJson()) {
                this.episode = num
                this.name = "Episode $num ${if(vost) "Vostfr" else "VF"}"
            }
        }
    }

    private fun cleanVideoUrl(url: String): String = url.replace("\\/", "/")
        .replace("f75s.com", "voe.sx").replace("bysezoxexe.com", "voe.sx")

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return "$mainUrl/$url".replace("//", "/").replace("https:/", "https://")
    }
}