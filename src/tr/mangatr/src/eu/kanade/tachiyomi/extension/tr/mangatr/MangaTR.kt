package eu.kanade.tachiyomi.extension.tr.mangatr

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
import java.util.Calendar
import java.util.Locale

@Source
abstract class MangaTR : HttpSource() {

    // www. eklentisi kaldırıldı (Kapakların bozulma sebebiydi)
    override val baseUrl = "https://manga-tr.com"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")

    override val client = network.client.newBuilder()
        .addInterceptor(::mangaShieldInterceptor)
        .addInterceptor(::coverInterceptor)
        .rateLimit(2)
        .build()

    private var captchaUrl: String? = null

    // Sadece HTML isteklerinde devreye giren Bot Koruması (Kapak resimlerinin engellenmesini çözer)
    private fun mangaShieldInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        val isHtml = response.header("Content-Type")?.contains("text/html") == true
        if (isHtml) {
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
        }
        return response
    }

    // ============================== Popular ==============================

    override fun popularMangaRequest(page: Int): Request {
        if (page > 1) return GET("$baseUrl/sayfa-yok.html", headers)
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

        // 1. Arama Ekranı (Eskiden çalışan ve kapakları getiren sağlam kısım)
        if (url.contains("arama.html") || url.contains("icerik=")) {
            document.select("div.arama-manga-list a.arama-manga-item").forEach { element ->
                val badges = element.select("span.la-badge").text().lowercase(Locale.ROOT)
                if (badges.contains("novel") || badges.contains("anime")) return@forEach

                val mangaTitle = element.selectFirst(".arama-manga-name")?.text() ?: element.text()
                if (mangaTitle.isEmpty()) return@forEach

                val slug = element.attr("manga-slug")
                mangas.add(
                    SManga.create().apply {
                        setUrlWithoutDomain(element.absUrl("href"))
                        title = mangaTitle
                        if (slug.isNotBlank()) thumbnail_url = "$baseUrl/fake-cover/$slug"
                    }
                )
            }
            return MangasPage(mangas, false)
        }

        // 2. Katalog Ekranı (Link bazlı evrensel tarayıcı)
        document.select("a").forEach { element ->
            val href = element.attr("href")
            // İçinde "manga-" geçen, "manga-list" olmayan ve html ile biten manga linklerini ayıklar
            if (href.contains("/manga-") && !href.contains("manga-list") && href.endsWith(".html")) {
                val mangaTitle = element.text().trim()
                if (mangaTitle.length > 1) {
                    val slug = href.substringAfter("/manga-").substringBefore(".html")
                    val mangaUrl = if (href.startsWith("http")) href else "$baseUrl$href"
                    
                    if (mangas.none { it.url == mangaUrl.replace(baseUrl, "") }) {
                        mangas.add(
                            SManga.create().apply {
                                setUrlWithoutDomain(mangaUrl)
                                title = mangaTitle
                                thumbnail_url = "$baseUrl/fake-cover/$slug"
                            }
                        )
                    }
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
        val id = manga.url.substringAfter("manga-").substringBefore(".html")

        // 1. Ana sayfadaki bölüm linklerini al
        val doc = client.newCall(GET(baseUrl + manga.url, headers)).execute().asJsoup()
        doc.select("a[href*=-read-]").filterNot { 
            it.hasClass("primary-button") || it.text().contains("İlk Bölüm", true) || it.text().contains("Son Bölüm", true) 
        }.forEach { element ->
            val chapter = parseChapterElement(element)
            if (chapters.none { it.url == chapter.url }) chapters.add(chapter)
        }

        // 2. AJAX (Eski sağlam yöntem: GET isteği atarak sayfa sayfa gez)
        var nextPage = 1
        val ajaxHeaders = headersBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Referer", baseUrl + manga.url)
            .build()

        while (true) {
            val requestUrl = "$baseUrl/cek/fetch_pages_manga.php?manga_cek=$id&page=$nextPage"
            val response = client.newCall(GET(requestUrl, ajaxHeaders)).execute()
            val ajaxDoc = response.asJsoup()
            ajaxDoc.setBaseUri(baseUrl)

            val ajaxLinks = ajaxDoc.select("a[href*=-read-]").filterNot { 
                it.hasClass("primary-button") || it.text().contains("İlk Bölüm", true) || it.text().contains("Son Bölüm", true) 
            }

            if (ajaxLinks.isEmpty()) break

            var addedNew = false
            ajaxLinks.forEach { element ->
                val chapter = parseChapterElement(element)
                if (chapters.none { it.url == chapter.url }) {
                    chapters.add(chapter)
                    addedNew = true
                }
            }
            
            // Eğer o sayfada yeni bir bölüm eklenmediyse döngüyü kır (Sonsuz döngüyü engeller)
            if (!addedNew) break
            nextPage++
        }
        
        chapters
    }

    private fun parseChapterElement(element: Element): SChapter {
        return SChapter.create().apply {
            val href = element.attr("href")
            setUrlWithoutDomain(if (href.startsWith("/")) href else "/$href")

            var text = element.text().trim()
            val specificTitle = element.parent()?.selectFirst(".chapter-number, .bento-ep-chapter-num, .chapter-title")?.text()?.trim()?.removeSuffix(".")
            val labelText = element.parent()?.selectFirst(".bento-ep-chapter-label")?.text()?.trim()

            name = when {
                specificTitle != null && labelText != null -> "$labelText $specificTitle"
                specificTitle != null -> "Bölüm $specificTitle"
                text.isNotBlank() -> text
                else -> {
                    val chapNum = href.substringAfter("-chapter-", "").substringBefore(".html")
                    if (chapNum.isNotBlank()) "Bölüm $chapNum" else "Bölüm"
                }
            }

            val dateText = element.parent()?.parent()?.selectFirst(".bento-ep-meta-time, .chapter-card__meta span, time")?.text()
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

        if (regexPages.isNotEmpty()) return regexPages

        val directImages = document.select("img[src*='img_part.php'], img[data-src*='img_part.php']")
        if (directImages.isNotEmpty()) {
            return directImages.mapIndexed { index, img ->
                val src = img.absUrl("src").ifEmpty { img.absUrl("data-src") }
                Page(index, imageUrl = src)
            }
        }

        return emptyList()
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
