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

    override val baseUrl = "https://manga-tr.com"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")

    // Agresif Bot Koruması Silindi! Sadece kapakları onaran araç (coverInterceptor) bırakıldı.
    override val client = network.client.newBuilder()
        .addInterceptor(::coverInterceptor)
        .rateLimit(2)
        .build()

    private var captchaUrl: String? = null

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

        // 1. Arama Ekranı (Kapakların düzgün çalıştığı kısım)
        if (url.contains("arama.html") || url.contains("icerik=")) {
            document.select("div.arama-manga-list a.arama-manga-item").forEach { element ->
                val badges = element.select("span.la-badge").text().lowercase(Locale.ROOT)
                if (badges.contains("novel") || badges.contains("anime")) return@forEach

                val mangaTitle = element.selectFirst(".arama-manga-name")?.text() ?: element.text()
                if (mangaTitle.isEmpty()) return@forEach

                val slug = element.attr("manga-slug")
                mangas.add(SManga.create().apply {
                    setUrlWithoutDomain(element.absUrl("href"))
                    title = mangaTitle.trim()
                    if (slug.isNotBlank()) thumbnail_url = "$baseUrl/fake-cover/$slug"
                })
            }
            return MangasPage(mangas, false)
        }

        // 2. Liste Ekranı (Temiz HTML Taraması)
        document.select("a.la-manga-item").forEach { element ->
            val badges = element.select("span.la-manga-badges").text().lowercase(Locale.ROOT)
            if (badges.contains("novel") || badges.contains("anime")) return@forEach

            val mangaTitle = element.selectFirst("span.la-manga-name")?.text() ?: element.text()
            val slug = element.attr("manga-slug")

            mangas.add(SManga.create().apply {
                setUrlWithoutDomain(element.absUrl("href"))
                title = mangaTitle.trim()
                if (slug.isNotBlank()) thumbnail_url = "$baseUrl/fake-cover/$slug"
            })
        }

        // Eğer HTML etiketleri değişmişse, düz linkleri tarayan son çare (Fallback)
        if (mangas.isEmpty()) {
            document.select("a[href^=manga-]:not([href*=manga-list])").forEach { element ->
                val href = element.attr("href")
                if (href.endsWith(".html")) {
                    val mangaTitle = element.text().trim()
                    if (mangaTitle.length > 1) {
                        val slug = href.substringAfter("manga-").substringBefore(".html")
                        mangas.add(SManga.create().apply {
                            setUrlWithoutDomain(if (href.startsWith("/")) href else "/$href")
                            title = mangaTitle
                            thumbnail_url = "$baseUrl/fake-cover/$slug"
                        })
                    }
                }
            }
        }

        // Aynı serileri (kopyaları) temizle
        val uniqueMangas = mangas.distinctBy { it.url }
        return MangasPage(uniqueMangas, false)
    }

    // ============================== Details ==============================

    override fun getMangaUrl(manga: SManga): String = captchaUrl?.also { captchaUrl = null } ?: super.getMangaUrl(manga)

    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val document = response.asJsoup()

        title = document.selectFirst("h1")?.text()?.replace(YEAR_REGEX, "") ?: throw Exception("Seri başlığı bulunamadı. Lütfen WebView üzerinden siteye girip doğrulama yapın.")
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

        // 1. Ana sayfadaki bölümleri tara
        val mainDoc = client.newCall(GET(baseUrl + manga.url, headers)).execute().asJsoup()
        chapters.addAll(parseChaptersFromDoc(mainDoc))

        // 2. Sayfalamayı (AJAX) Tara
        val ajaxHeaders = headersBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Referer", baseUrl + manga.url)
            .build()

        // Sitenin JWT (POST) anahtarı varsa onu, yoksa eski (GET) sayfa numarasını kullanır
        val firstPaginationLink = mainDoc.selectFirst("nav.pagination-wrap a.pagination-link[data-key]")
        var nextKey = firstPaginationLink?.attr("data-key")?.takeIf { it.isNotBlank() }
        var nextPage = 2 

        if (nextKey != null) {
            // YENİ SİSTEM: POST İsteği
            while (nextKey != null) {
                val requestUrl = "$baseUrl/cek/fetch_pages_manga.php"
                val postBody = FormBody.Builder().add("chapter_list_key", nextKey).build()

                val response = client.newCall(POST(requestUrl, ajaxHeaders, postBody)).execute()
                val ajaxDoc = response.asJsoup()
                ajaxDoc.setBaseUri(baseUrl)

                val newChapters = parseChaptersFromDoc(ajaxDoc)
                if (newChapters.isEmpty()) break

                var added = false
                for (ch in newChapters) {
                    if (chapters.none { it.url == ch.url }) {
                        chapters.add(ch)
                        added = true
                    }
                }
                if (!added) break

                val activePage = ajaxDoc.selectFirst("nav.pagination-wrap a.pagination-link.is-active")
                val currentNum = activePage?.text()?.toIntOrNull() ?: (nextPage - 1)
                val nextLink = ajaxDoc.selectFirst("nav.pagination-wrap a.pagination-link[data-page=${currentNum + 1}]")
                nextKey = nextLink?.attr("data-key")?.takeIf { it.isNotBlank() }
                nextPage++
            }
        } else {
            // ESKİ SİSTEM: GET İsteği (Ana sayfada bölüm yoksa 1. sayfadan başlar)
            nextPage = if (chapters.isEmpty()) 1 else 2
            while (true) {
                val requestUrl = "$baseUrl/cek/fetch_pages_manga.php?manga_cek=$id&page=$nextPage"
                val response = client.newCall(GET(requestUrl, ajaxHeaders)).execute()
                val ajaxDoc = response.asJsoup()
                ajaxDoc.setBaseUri(baseUrl)

                val newChapters = parseChaptersFromDoc(ajaxDoc)
                if (newChapters.isEmpty()) break

                var added = false
                for (ch in newChapters) {
                    if (chapters.none { it.url == ch.url }) {
                        chapters.add(ch)
                        added = true
                    }
                }
                if (!added) break

                val hasNext = ajaxDoc.selectFirst("nav.pagination-wrap a.pagination-link[data-page=${nextPage + 1}]") != null
                if (!hasNext) break
                nextPage++
            }
        }

        chapters
    }

    private fun parseChaptersFromDoc(doc: org.jsoup.nodes.Document): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        val elements = doc.select("article.bento-ep-card, article.chapter-card")

        // Eğer HTML kutuları (Bento Card vb.) varsa onları oku
        if (elements.isNotEmpty()) {
            elements.forEach { el ->
                val link = el.selectFirst("a.bento-ep-title-link, a.chapter-card__row, a.chapter-card__title, a[href*=-read-]") ?: return@forEach
                val href = link.attr("href")
                
                if (href.contains("id-") || href.contains("-read-")) {
                    chapters.add(SChapter.create().apply {
                        setUrlWithoutDomain(if (href.startsWith("/")) href else "/$href")
                        
                        val numText = el.selectFirst(".bento-ep-chapter-num, .chapter-number")?.text()?.trim()?.removeSuffix(".")
                        val labelText = el.selectFirst(".bento-ep-chapter-label")?.text()?.trim()
                        val specificTitle = el.selectFirst(".chapter-title span")?.text()?.trim()

                        name = when {
                            numText != null && labelText != null -> "$labelText $numText".trim()
                            numText != null -> "Bölüm $numText"
                            specificTitle != null -> specificTitle
                            else -> link.text().trim().takeIf { it.isNotBlank() } ?: "Bölüm"
                        }

                        val dateText = el.selectFirst(".bento-ep-meta-time, .chapter-card__meta span")?.text()
                        date_upload = parseRelativeDate(dateText)
                    })
                }
            }
        } else {
            // Eğer HTML kutuları değişmişse sadece düz bölüm linklerini yakala (Zırh Delici Fallback)
            doc.select("a[href*=-read-]").filterNot {
                it.hasClass("primary-button") || it.text().contains("İlk Bölüm", true) || it.text().contains("Son Bölüm", true)
            }.forEach { link ->
                val href = link.attr("href")
                chapters.add(SChapter.create().apply {
                    setUrlWithoutDomain(if (href.startsWith("/")) href else "/$href")
                    val text = link.text().trim()
                    name = if (text.isNotBlank()) text else {
                        val chapNum = href.substringAfter("-chapter-", "").substringBefore(".html")
                        if (chapNum.isNotBlank()) "Bölüm $chapNum" else "Bölüm"
                    }
                })
            }
        }
        return chapters
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
