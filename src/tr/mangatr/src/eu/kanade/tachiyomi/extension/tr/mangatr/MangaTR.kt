package eu.kanade.tachiyomi.extension.tr.mangatr

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.parseAs
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Element
import rx.Observable
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.Locale

@Source
abstract class MangaTR : HttpSource() {

    // Cookie ve yönlendirme sorunlarını aşmak için www eklendi
    override val baseUrl = "https://www.manga-tr.com"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")

    override val client = network.client.newBuilder()
        .addInterceptor(::mangaShieldInterceptor)
        .addInterceptor(::coverInterceptor)
        .rateLimit(2)
        .build()

    private var captchaUrl: String? = null

    private fun mangaShieldInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        val bodyText = response.peekBody(4096).string()
        if (response.code == 403 || response.code == 503 ||
            bodyText.contains("Manga Shield", ignoreCase = true) ||
            bodyText.contains("Güvenlik Kontrolü", ignoreCase = true) ||
            bodyText.contains("cf-turnstile") ||
            bodyText.contains("Just a moment...")
        ) {
            response.close()
            throw IOException("Lütfen WebView üzerinden 'Manga Shield' bot korumasını geçin.")
        }

        return response
    }

    // ============================== Popular ==============================

    override fun popularMangaRequest(page: Int): Request {
        if (page > 1) return GET("$baseUrl/sayfa-yok.html", headers) // Tüm liste JSON ile tek seferde çekilir
        return GET("$baseUrl/manga-list.html", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = searchMangaParse(response)

    // ============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        if (page > 1) return GET("$baseUrl/sayfa-yok.html", headers)
        return GET("$baseUrl/manga-list.html", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = searchMangaParse(response)

    // ============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            val url = "$baseUrl/arama.html".toHttpUrl().newBuilder()
                .addQueryParameter("icerik", query)
                .build()
            return GET(url, headers)
        }
        return GET("$baseUrl/manga-list.html", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val url = response.request.url.toString()
        val mangas = mutableListOf<SManga>()

        // 1. Arama Ekranı
        if (url.contains("arama.html") || url.contains("icerik=")) {
            document.select("div.arama-manga-list a.arama-manga-item").forEach { element ->
                val badges = element.select("span.la-badge").text().lowercase(Locale.ROOT)
                if (badges.contains("novel") || badges.contains("anime")) return@forEach
                
                val mangaTitle = element.selectFirst(".arama-manga-name")?.text() ?: element.text()
                if (mangaTitle.isEmpty()) return@forEach

                val slug = element.attr("manga-slug")
                mangas.add(SManga.create().apply {
                    setUrlWithoutDomain(element.absUrl("href"))
                    title = mangaTitle
                    if (slug.isNotBlank()) thumbnail_url = "$baseUrl/fake-cover/$slug"
                })
            }
            return MangasPage(mangas, false)
        }

        // 2. Katalog Ekranı
        // A) Ekranda Görünenler
        document.select("a.la-manga-item").forEach { element ->
            val badges = element.select("span.la-manga-badges").text().lowercase(Locale.ROOT)
            if (badges.contains("novel") || badges.contains("anime")) return@forEach
            
            val mangaTitle = element.selectFirst("span.la-manga-name")?.text() ?: return@forEach
            val slug = element.attr("manga-slug")
            
            mangas.add(SManga.create().apply {
                setUrlWithoutDomain(element.absUrl("href"))
                title = mangaTitle
                if (slug.isNotBlank()) thumbnail_url = "$baseUrl/fake-cover/$slug"
            })
        }
        
        // B) Sayfa Arkasına Gizlenmiş JSON Verisini Çekme (10.000+ Seri)
        document.select("div.la-manga-list-hidden").forEach { hiddenDiv ->
            val jsonStr = hiddenDiv.attr("data-hidden-items")
            
            val pattern = """\{"name":"(.*?)","slug":"([^"]+)"""".toRegex()
            pattern.findAll(jsonStr).forEach { matchResult ->
                val titleRaw = matchResult.groupValues[1]
                    .replace("\\\"", "\"").replace("\\\\", "\\")
                    .replace("&quot;", "\"").replace("&#039;", "'").replace("&amp;", "&")
                val slugRaw = matchResult.groupValues[2]
                
                val mangaUrl = "/manga-$slugRaw.html"
                if (mangas.none { it.url == mangaUrl }) {
                    mangas.add(SManga.create().apply {
                        setUrlWithoutDomain(mangaUrl)
                        title = titleRaw
                        thumbnail_url = "$baseUrl/fake-cover/$slugRaw"
                    })
                }
            }
        }

        return MangasPage(mangas, false)
    }

    // ============================== Details ==============================

    override fun getMangaUrl(manga: SManga): String = captchaUrl?.also { captchaUrl = null } ?: super.getMangaUrl(manga)

    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val document = response.asJsoup()

        title = document.selectFirst("h1")?.text()?.replace(YEAR_REGEX, "") ?: throw Exception("Seri başlığı bulunamadı (WebView'i kontrol edin)")
        thumbnail_url = document.selectFirst(".poster-card__image")?.absUrl("src")

        val descBlock = document.selectFirst("#manga-description, .detail-copy")?.text()
        val altNames = document.selectFirst(".detail-hero__sub")?.text()
        description = buildString {
            if (!descBlock.isNullOrEmpty()) append(descBlock)
            if (!altNames.isNullOrEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Alternatif İsimler: ")
                append(altNames)
            }
        }

        author = document.select(".detail-meta-row:contains(Yazar) .detail-meta-row__value a").joinToString { it.text() }
        artist = document.select(".detail-meta-row:contains(Sanatçı) .detail-meta-row__value a").joinToString { it.text() }
        genre = document.select(".detail-meta-row:contains(Tür) .detail-meta-row__value a").joinToString { it.text() }

        val statusText = document.selectFirst(".detail-meta-row:contains(Yayın durumu) .detail-meta-row__value")?.text()?.lowercase(Locale.ROOT)
        status = when {
            statusText?.contains("devam") == true -> SManga.ONGOING
            statusText?.contains("tamamlan") == true -> SManga.COMPLETED
            statusText?.contains("bırak") == true || statusText?.contains("iptal") == true -> SManga.CANCELLED
            statusText?.contains("askı") == true -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }

    // ============================= Chapters ==============================

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val chapters = mutableListOf<SChapter>()
        
        val doc = client.newCall(GET(baseUrl + manga.url, headers)).execute().asJsoup()
        
        // 1. Yeni Tasarım (Bento Card) ve Eski Tasarım (Chapter Card) Taraması
        doc.select("article.bento-ep-card, article.chapter-card").forEach { element ->
            chapters.add(parseChapterElement(element))
        }

        // 2. Şifreli Jeton ile AJAX Üzerinden Sonraki Sayfaları Çekme (POST İsteği)
        var nextKey = getNextPageKey(doc)
        
        val ajaxHeaders = headersBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Referer", baseUrl + manga.url)
            .add("Accept", "text/html, */*; q=0.01")
            .build()

        while (nextKey != null) {
            val requestUrl = "$baseUrl/cek/fetch_pages_manga.php"
            val postBody = FormBody.Builder()
                .add("chapter_list_key", nextKey)
                .build()
                
            val response = client.newCall(POST(requestUrl, ajaxHeaders, postBody)).execute()
            val ajaxDoc = response.asJsoup()
            ajaxDoc.setBaseUri(baseUrl)

            ajaxDoc.select("article.bento-ep-card, article.chapter-card").forEach { element ->
                val chapter = parseChapterElement(element)
                if (chapters.none { it.url == chapter.url }) {
                    chapters.add(chapter)
                }
            }
            
            nextKey = getNextPageKey(ajaxDoc)
        }
        
        chapters
    }

    private fun getNextPageKey(document: org.jsoup.nodes.Document): String? {
        val activePage = document.selectFirst("nav.pagination-wrap a.pagination-link.is-active") ?: return null
        val nextNum = (activePage.text().toIntOrNull() ?: 1) + 1
        val nextLink = document.selectFirst("nav.pagination-wrap a.pagination-link[data-page=$nextNum]")
        return nextLink?.attr("data-key")?.takeIf { it.isNotBlank() }
    }

    private fun parseChapterElement(element: Element): SChapter {
        return SChapter.create().apply {
            val link = element.selectFirst("a.bento-ep-title-link") ?: element.selectFirst("a[href*=-read-], a.chapter-card__row, a.chapter-card__title")!!
            val href = link.attr("href")
            setUrlWithoutDomain(if (href.startsWith("/")) href else "/$href")

            val numText = element.selectFirst(".bento-ep-chapter-num, .chapter-number")?.text()?.trim()?.removeSuffix(".")
            val labelText = element.selectFirst(".bento-ep-chapter-label")?.text()?.trim()
            val specificTitle = element.selectFirst(".chapter-title span")?.text()?.trim()

            name = when {
                numText != null && labelText != null -> "$labelText $numText".trim()
                numText != null -> "Bölüm $numText"
                specificTitle != null -> specificTitle
                else -> link.text().trim()
            }

            val dateText = element.selectFirst(".bento-ep-meta-time, .chapter-card__meta span")?.text()
            date_upload = parseRelativeDate(dateText)
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException("Not used.")

    // =============================== Pages ===============================

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        if (document.selectFirst("div#uyari:contains(üye girişi)") != null) {
            throw IOException("Bu bölümü okuyabilmek için WebView üzerinden üye girişi yapmanız gerekmektedir.")
        }

        val pages = mutableListOf<Page>()
        val chapterPages = document.select("div.chapter-page")

        if (chapterPages.isNotEmpty()) {
            val sortedChapterPages = chapterPages
                .filter { it.hasAttr("data-parts") && it.hasAttr("data-order") }
                .sortedBy { it.attr("data-page-index").toIntOrNull() ?: Int.MAX_VALUE }

            for (page in sortedChapterPages) {
                val partsJson = page.attr("data-parts")
                val orderAttr = page.attr("data-order")

                val urls: List<String> = runCatching {
                    partsJson.parseAs<List<String>>()
                }.getOrElse { emptyList() }

                if (urls.isEmpty()) continue

                val mapping = decodePartOrderMapping(orderAttr)
                if (mapping.isNullOrEmpty()) {
                    pages.add(Page(pages.size, imageUrl = urls.first()))
                    continue
                }

                val sortedUrls = mapping
                    .sortedBy { it.second }
                    .mapNotNull { (partIdx, _) -> urls.getOrNull(partIdx) }

                if (sortedUrls.isEmpty()) {
                    pages.add(Page(pages.size, imageUrl = urls.first()))
                    continue
                }

                for (url in sortedUrls) {
                    pages.add(Page(pages.size, imageUrl = url))
                }
            }

            if (pages.isNotEmpty()) return pages
        }

        val directImages = document.select("img[src*='img_part.php'], img[data-src*='img_part.php']")
        if (directImages.isNotEmpty()) {
            return directImages.mapIndexed { index, img ->
                val src = img.absUrl("src").ifEmpty { img.absUrl("data-src") }
                Page(index, imageUrl = src)
            }
        }

        val html = document.html()
        val seenKeys = mutableSetOf<String>()

        val regexPages = IMG_URL_REGEX.findAll(html)
            .map { it.value.replace("&amp;", "&") }
            .filterNot { it.contains("logo") }
            .filter { url ->
                val keyMatch = KEY_REGEX.find(url)
                val key = keyMatch?.groupValues?.get(1) ?: return@filter false
                if (seenKeys.contains(key)) {
                    false
                } else {
                    seenKeys.add(key)
                    true
                }
            }
            .mapIndexed { idx, url -> Page(idx, imageUrl = url) }
            .toList()

        return regexPages
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used.")

    // ============================== Filters ==============================

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
        SortDirectionFilter(),
        GenreFilter(),
        StatusFilter(),
        TranslationStatusFilter(),
        AgeFilter(),
        ContentTypeFilter()
    )

    // ============================= Utilities =============================

    private fun coverInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.pathSegments.firstOrNull() == "fake-cover") {
            val slug = request.url.pathSegments.last()

            val popHeaders = headersBuilder()
                .add("X-Requested-With", "XMLHttpRequest")
                .add("Referer", "$baseUrl/arama.html")
                .build()

            val popRequest = POST(
                "$baseUrl/app/manga/controllers/cont.pop.php",
                popHeaders,
                FormBody.Builder().add("slug", slug).build()
            )

            val realCoverUrl = try {
                chain.proceed(popRequest).use { response ->
                    if (!response.isSuccessful) return@use null
                    response.asJsoup().selectFirst("img")?.absUrl("src")
                }
            } catch (_: Exception) {
                null
            }

            if (realCoverUrl.isNullOrEmpty()) {
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Cover not found")
                    .body("".toResponseBody("image/png".toMediaType()))
                    .build()
            }

            val realRequest = GET(realCoverUrl, request.headers)
            return chain.proceed(realRequest)
        }

        return chain.proceed(request)
    }

    private fun decodePartOrderMapping(encoded: String): List<Pair<Int, Int>>? {
        val raw = try {
            Base64.decode(encoded, Base64.DEFAULT)
        } catch (_: Exception) {
            return null
        }
        val decoded = ByteArray(raw.size) { i -> ((raw[i].toInt() and 0xFF) xor 0x5A).toByte() }
        val jsonStr = String(decoded, StandardCharsets.UTF_8)

        return runCatching {
            jsonStr.parseAs<List<Int>>().mapIndexed { idx, pos -> idx to pos }
        }.getOrNull()
            ?: runCatching {
                jsonStr.parseAs<Map<String, Int>>().mapNotNull { (k, v) ->
                    val partIdx = k.toIntOrNull() ?: return@mapNotNull null
                    partIdx to v
                }
            }.getOrNull()
            ?: runCatching {
                jsonStr.parseAs<List<String>>().mapIndexedNotNull { idx, pos ->
                    idx to (pos.toIntOrNull() ?: return@mapIndexedNotNull null)
                }
            }.getOrNull()
            ?: runCatching {
                jsonStr.parseAs<Map<String, String>>().mapNotNull { (k, v) ->
                    val partIdx = k.toIntOrNull() ?: return@mapNotNull null
                    val pos = v.toIntOrNull() ?: return@mapNotNull null
                    partIdx to pos
                }
            }.getOrNull()
    }

    private fun parseRelativeDate(dateString: String?): Long {
        if (dateString == null) return 0L
        val trimmed = dateString.lowercase(Locale.ROOT)
        val number = NUMBER_REGEX.find(trimmed)?.value?.toIntOrNull() ?: return 0L
        val cal = Calendar.getInstance()
        when {
            trimmed.contains("saniye") -> cal.add(Calendar.SECOND, -number)
            trimmed.contains("dakika") || trimmed.contains("dk") -> cal.add(Calendar.MINUTE, -number)
            trimmed.contains("saat") || trimmed.contains("sa") -> cal.add(Calendar.HOUR, -number)
            trimmed.contains("gün") -> cal.add(Calendar.DAY_OF_YEAR, -number)
            trimmed.contains("hafta") -> cal.add(Calendar.WEEK_OF_YEAR, -number)
            trimmed.contains("ay") -> cal.add(Calendar.MONTH, -number)
            trimmed.contains("yıl") || trimmed.contains("yil") -> cal.add(Calendar.YEAR, -number)
            else -> return 0L
        }
        return cal.timeInMillis
    }

    companion object {
        private val YEAR_REGEX = Regex("""\s*\(\d{4}\)$""")
        private val NUMBER_REGEX = Regex("""\d+""")
        private val IMG_URL_REGEX = Regex("""https?://[^"'\s]*img_part\.php[^"'\s]*""")
        private val KEY_REGEX = Regex("""key=([^&]+)""")
    }
}
