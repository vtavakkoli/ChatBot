package ai.chatbot.litert;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Better InternetSearchClient for small local Android LLMs.
 *
 * Improvements over the previous version:
 * - The search result snippet is not trusted as final evidence.
 * - Pages are opened, cleaned, chunked, and ranked globally by query relevance.
 * - Image evidence is extracted from og:image/twitter:image/img alt/title/nearby text.
 * - The prompt explicitly tells the model when image evidence is only metadata.
 *
 * Important: call search(...) from a background thread, not the Android UI thread.
 */
public class InternetSearchClient {
    private static final int MAX_CANDIDATES = 26;
    private static final int MAX_OPENED_LINKS = 10;
    private static final int MAX_TOP_CHUNKS = 10;
    private static final int MAX_TOP_IMAGES = 6;
    private static final int MAX_PROMPT_CONTEXT_CHARS = 5200;
    private static final int MAX_SOURCE_CHARS = 520;
    private static final int MAX_DOWNLOAD_CHARS = 650000;

    public static class SearchResult {
        public final String contextForPrompt;
        public final String displayText;
        public final boolean hasUsefulResults;
        public final List<ImageSource> imageSources;
        public final List<SourceLink> sourceLinks;

        /**
         * If non-empty, this is a deterministic answer produced from structured data
         * such as Yahoo/Stooq quote JSON/CSV. For live price questions, prefer showing
         * this directly instead of asking a tiny local LLM to infer the answer.
         */
        public final String directAnswer;

        public SearchResult(String contextForPrompt, String displayText, boolean hasUsefulResults) {
            this(contextForPrompt, displayText, hasUsefulResults, new ArrayList<ImageSource>(), "", new ArrayList<SourceLink>());
        }

        public SearchResult(String contextForPrompt, String displayText, boolean hasUsefulResults, List<ImageSource> imageSources) {
            this(contextForPrompt, displayText, hasUsefulResults, imageSources, "", new ArrayList<SourceLink>());
        }

        public SearchResult(String contextForPrompt, String displayText, boolean hasUsefulResults, List<ImageSource> imageSources, String directAnswer) {
            this(contextForPrompt, displayText, hasUsefulResults, imageSources, directAnswer, new ArrayList<SourceLink>());
        }

        public SearchResult(String contextForPrompt, String displayText, boolean hasUsefulResults, List<ImageSource> imageSources, String directAnswer, List<SourceLink> sourceLinks) {
            this.contextForPrompt = contextForPrompt;
            this.displayText = displayText;
            this.hasUsefulResults = hasUsefulResults;
            this.imageSources = imageSources == null ? new ArrayList<ImageSource>() : imageSources;
            this.directAnswer = directAnswer == null ? "" : directAnswer;
            this.sourceLinks = sourceLinks == null ? new ArrayList<SourceLink>() : sourceLinks;
        }

        public Map<String, String> sourceLinksByLabel() {
            Map<String, String> links = new LinkedHashMap<>();
            for (SourceLink sourceLink : sourceLinks) {
                if (sourceLink == null || sourceLink.label.isEmpty() || sourceLink.url.isEmpty()) continue;
                links.put(sourceLink.label, sourceLink.url);
            }
            return links;
        }
    }

    public static class SourceLink {
        public final String label;
        public final String title;
        public final String url;
        public final String sourceType;

        SourceLink(String label, String title, String url, String sourceType) {
            this.label = clean(label);
            this.title = clean(title);
            this.url = clean(url);
            this.sourceType = clean(sourceType);
        }
    }

    public static class ImageSource {
        public final String imageUrl;
        public final String pageUrl;
        public final String pageTitle;
        public final String altText;
        public final String captionOrNearbyText;
        public final double score;

        ImageSource(String imageUrl, String pageUrl, String pageTitle, String altText, String captionOrNearbyText, double score) {
            this.imageUrl = clean(imageUrl);
            this.pageUrl = clean(pageUrl);
            this.pageTitle = clean(pageTitle);
            this.altText = clean(altText);
            this.captionOrNearbyText = clean(captionOrNearbyText);
            this.score = score;
        }
    }

    private static class SearchItem {
        final String title;
        final String snippet;
        final String url;
        final String source;
        String finalUrl = "";
        String fetchStatus = "not opened";
        final List<TextChunk> chunks = new ArrayList<>();
        final List<ImageSource> images = new ArrayList<>();

        SearchItem(String title, String snippet, String url, String source) {
            this.title = clean(title);
            this.snippet = clean(snippet);
            this.url = clean(url);
            this.source = source == null ? "Web" : clean(source);
        }
    }

    private static class TextChunk {
        final SearchItem item;
        final String text;
        final double score;

        TextChunk(SearchItem item, String text, double score) {
            this.item = item;
            this.text = clean(text);
            this.score = score;
        }
    }

    private static class MarketQuote {
        final String label;
        final String price;
        final String currency;
        final String timestamp;
        final String provider;
        final String url;
        final String extra;

        MarketQuote(String label, String price, String currency, String timestamp, String provider, String url, String extra) {
            this.label = clean(label);
            this.price = clean(price);
            this.currency = clean(currency);
            this.timestamp = clean(timestamp);
            this.provider = clean(provider);
            this.url = clean(url);
            this.extra = clean(extra);
        }

        String evidence() {
            StringBuilder e = new StringBuilder();
            e.append("Structured live market quote: ").append(label);
            if (!price.isEmpty()) e.append(" = ").append(price);
            if (!currency.isEmpty()) e.append(" ").append(currency);
            if (!timestamp.isEmpty()) e.append(", timestamp ").append(timestamp);
            if (!provider.isEmpty()) e.append(", provider ").append(provider);
            if (!extra.isEmpty()) e.append(". ").append(extra);
            e.append(". Market data can be delayed.");
            return clean(e.toString());
        }

        String answer() {
            StringBuilder a = new StringBuilder();
            a.append(label).append(": ").append(price);
            if (!currency.isEmpty()) a.append(" ").append(currency);
            if (!timestamp.isEmpty()) a.append(" (latest available timestamp: ").append(timestamp).append(")");
            if (!provider.isEmpty()) a.append(". Source: ").append(provider).append(" [S1].");
            else a.append(". Source: [S1].");
            a.append(" Market data may be delayed.");
            return clean(a.toString());
        }
    }

    private static class DownloadResult {
        final String text;
        final String finalUrl;
        final String contentType;

        DownloadResult(String text, String finalUrl, String contentType) {
            this.text = text == null ? "" : text;
            this.finalUrl = finalUrl == null ? "" : finalUrl;
            this.contentType = contentType == null ? "" : contentType;
        }
    }

    public SearchResult search(String query) throws Exception {
        String q = clean(query);
        List<String> debug = new ArrayList<>();
        List<SearchItem> items = new ArrayList<>();

        if (q.isEmpty()) {
            return new SearchResult("No query was provided.", "No query was provided.", false);
        }

        MarketQuote directQuote = tryAddMarketQuote(q, items, debug);

        // Do normal web/image RAG too, but for quote questions the structured quote wins.
        tryAddDuckDuckGoLite(q, items, debug);
        tryAddDuckDuckGoInstant(q, items, debug);
        if (looksCurrentQuestion(q) || countLinks(items) < 5) tryAddGoogleNews(q, items, debug);

        List<SearchItem> unique = dedupe(items, MAX_CANDIDATES);
        fetchPagesAndBuildEvidence(q, unique, debug);

        List<TextChunk> topChunks = rankChunks(unique);
        List<ImageSource> topImages = rankImages(unique);

        if (topChunks.isEmpty()) addSnippetFallbackChunks(q, unique, topChunks);
        boolean ok = !topChunks.isEmpty() || !topImages.isEmpty();
        if (!ok) {
            String notes = debug.isEmpty() ? "No useful public web evidence was retrieved." : join(debug, "; ");
            return new SearchResult("WEB RAG FAILED. " + notes, "Web/image RAG failed. " + notes, false);
        }

        String directAnswer = directQuote == null ? "" : directQuote.answer();
        List<SourceLink> sourceLinks = buildSourceLinks(topChunks, topImages);
        return new SearchResult(
                buildPromptContext(q, topChunks, topImages, debug, directAnswer),
                buildDisplayText(topChunks, topImages, debug, directAnswer),
                true,
                topImages,
                directAnswer,
                sourceLinks
        );
    }

    private void tryAddDuckDuckGoLite(String query, List<SearchItem> items, List<String> debug) {
        try {
            String url = "https://lite.duckduckgo.com/lite/?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8.name());
            String html = downloadText(url, "text/html,*/*", 420000).text;
            Pattern p = Pattern.compile("(?is)<a[^>]*class=[\"'][^\"']*result-link[^\"']*[\"'][^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>");
            Matcher m = p.matcher(html);
            int found = 0;
            while (m.find() && found < 14) {
                String href = normalizeDuckDuckGoUrl(m.group(1));
                String title = stripHtml(m.group(2));
                String tail = html.substring(m.end(), Math.min(html.length(), m.end() + 1800));
                String snippet = stripHtml(extractFirst(tail, "(?is)<td[^>]*class=[\"'][^\"']*result-snippet[^\"']*[\"'][^>]*>(.*?)</td>"));
                if (isFetchableUrl(href) && !title.isEmpty() && !looksLikeNavigation(title)) {
                    items.add(new SearchItem(title, snippet, href, "DuckDuckGo Lite"));
                    found++;
                }
            }
            if (found == 0) {
                Pattern fb = Pattern.compile("(?is)<a[^>]+href=[\"']([^\"']*uddg=[^\"']+)[\"'][^>]*>(.*?)</a>");
                Matcher fm = fb.matcher(html);
                while (fm.find() && found < 14) {
                    String href = normalizeDuckDuckGoUrl(fm.group(1));
                    String title = stripHtml(fm.group(2));
                    if (isFetchableUrl(href) && !title.isEmpty() && !looksLikeNavigation(title)) {
                        items.add(new SearchItem(title, "", href, "DuckDuckGo Lite"));
                        found++;
                    }
                }
            }
        } catch (Exception e) {
            debug.add("DuckDuckGo Lite failed: " + e.getClass().getSimpleName());
        }
    }

    private void tryAddDuckDuckGoInstant(String query, List<SearchItem> items, List<String> debug) {
        try {
            String url = "https://api.duckduckgo.com/?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8.name()) + "&format=json&no_html=1&skip_disambig=0";
            JSONObject json = new JSONObject(downloadText(url, "application/json,*/*", 300000).text);
            String abs = json.optString("AbstractText", "");
            String heading = json.optString("Heading", "");
            String absUrl = json.optString("AbstractURL", "");
            if (!abs.trim().isEmpty()) {
                SearchItem item = new SearchItem(heading.isEmpty() ? "DuckDuckGo instant answer" : heading, abs, absUrl, "DuckDuckGo Instant Answer");
                item.fetchStatus = "instant answer";
                item.chunks.add(new TextChunk(item, abs, 900));
                items.add(item);
            }
            flattenRelatedTopics(json.optJSONArray("RelatedTopics"), items, 0);
        } catch (Exception e) {
            debug.add("DuckDuckGo API failed: " + e.getClass().getSimpleName());
        }
    }

    private void flattenRelatedTopics(JSONArray arr, List<SearchItem> out, int depth) {
        if (arr == null || depth > 2 || out.size() >= MAX_CANDIDATES) return;
        for (int i = 0; i < arr.length() && out.size() < MAX_CANDIDATES; i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) continue;
            String text = obj.optString("Text", "");
            String url = obj.optString("FirstURL", "");
            if (!text.trim().isEmpty()) out.add(new SearchItem(titleFromText(text), text, url, "DuckDuckGo RelatedTopic"));
            flattenRelatedTopics(obj.optJSONArray("Topics"), out, depth + 1);
        }
    }

    private void tryAddGoogleNews(String query, List<SearchItem> items, List<String> debug) {
        try {
            String newsQuery = looksCurrentQuestion(query) ? query + " when:7d" : query;
            String url = "https://news.google.com/rss/search?q=" + URLEncoder.encode(newsQuery, StandardCharsets.UTF_8.name()) + "&hl=en-US&gl=US&ceid=US:en";
            String xml = downloadText(url, "application/rss+xml,application/xml,text/xml,*/*", 260000).text;
            Matcher m = Pattern.compile("(?is)<item>(.*?)</item>").matcher(xml);
            int found = 0;
            while (m.find() && found < 8) {
                String block = m.group(1);
                String title = xmlTag(block, "title");
                String link = xmlTag(block, "link");
                String desc = removeUrls(stripHtml(xmlTag(block, "description")));
                String date = xmlTag(block, "pubDate");
                if (!title.isEmpty()) {
                    String cleanTitle = stripHtml(title);
                    // For live price queries, old prediction articles are harmful evidence.
                    if (looksCurrentQuestion(query) && looksStaleNewsTitle(cleanTitle)) continue;
                    items.add(new SearchItem(cleanTitle, clean(desc + " " + date), shorten(link, 180), "Google News RSS"));
                    found++;
                }
            }
        } catch (Exception e) {
            debug.add("Google News failed: " + e.getClass().getSimpleName());
        }
    }

    private MarketQuote tryAddMarketQuote(String query, List<SearchItem> items, List<String> debug) {
        String lower = query.toLowerCase(Locale.US);
        String[] yahooSymbols;
        String[] stooqSymbols;
        String label;

        if (containsAny(lower, "gold", "xau", "xauusd")) {
            // XAUUSD=X is spot gold if Yahoo serves it; GC=F is COMEX futures fallback.
            yahooSymbols = new String[]{"XAUUSD=X", "GC=F"};
            stooqSymbols = new String[]{"xauusd", "d:xauusd", "gc.f"};
            label = "Gold price";
        } else if (containsAny(lower, "silver", "xag", "xagusd")) {
            yahooSymbols = new String[]{"XAGUSD=X", "SI=F"};
            stooqSymbols = new String[]{"xagusd", "d:xagusd", "si.f"};
            label = "Silver price";
        } else if (containsAny(lower, "bitcoin", "btc", "btcusd")) {
            yahooSymbols = new String[]{"BTC-USD"};
            stooqSymbols = new String[]{"btcusd"};
            label = "Bitcoin price";
        } else if (containsAny(lower, "ethereum", "eth", "ethusd")) {
            yahooSymbols = new String[]{"ETH-USD"};
            stooqSymbols = new String[]{"ethusd"};
            label = "Ethereum price";
        } else if (containsAny(lower, "eur usd", "eurusd", "euro dollar")) {
            yahooSymbols = new String[]{"EURUSD=X"};
            stooqSymbols = new String[]{"eurusd", "d:eurusd"};
            label = "EUR/USD exchange rate";
        } else {
            return null;
        }

        // 1) Yahoo chart JSON usually works better on Android than scraping finance pages.
        for (String ys : yahooSymbols) {
            try {
                MarketQuote q = fetchYahooQuote(label, ys);
                if (q != null && !q.price.isEmpty()) {
                    addMarketQuoteItem(q, items);
                    return q;
                }
            } catch (Exception e) {
                debug.add("Yahoo quote failed for " + ys + ": " + e.getClass().getSimpleName() + " " + shorten(e.getMessage(), 70));
            }
        }

        // 2) Stooq CSV fallback. It sometimes requires d: prefix depending on symbol/data server.
        for (String ss : stooqSymbols) {
            try {
                MarketQuote q = fetchStooqCsvQuote(label, ss);
                if (q != null && !q.price.isEmpty()) {
                    addMarketQuoteItem(q, items);
                    return q;
                }
            } catch (Exception e) {
                debug.add("Stooq quote failed for " + ss + ": " + e.getClass().getSimpleName() + " " + shorten(e.getMessage(), 70));
            }
        }

        debug.add("structured market quote unavailable; falling back to web snippets");
        return null;
    }

    private void addMarketQuoteItem(MarketQuote quote, List<SearchItem> items) {
        SearchItem item = new SearchItem(quote.label, quote.evidence(), quote.url, "Structured market quote");
        item.fetchStatus = "direct structured quote - use this before news snippets";
        item.finalUrl = quote.url;
        item.chunks.add(new TextChunk(item, quote.evidence(), 9999));
        items.add(item);
    }

    private MarketQuote fetchYahooQuote(String label, String symbol) throws Exception {
        String enc = URLEncoder.encode(symbol, StandardCharsets.UTF_8.name());
        String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + enc + "?range=1d&interval=1m";
        String text = downloadText(url, "application/json,text/plain,*/*", 260000).text;
        JSONObject root = new JSONObject(text);
        JSONObject chart = root.optJSONObject("chart");
        if (chart == null) throw new IllegalStateException("missing chart object");
        JSONArray result = chart.optJSONArray("result");
        if (result == null || result.length() == 0 || result.isNull(0)) throw new IllegalStateException("empty chart result");
        JSONObject meta = result.getJSONObject(0).optJSONObject("meta");
        if (meta == null) throw new IllegalStateException("missing meta");

        double price = meta.optDouble("regularMarketPrice", Double.NaN);
        if (Double.isNaN(price) || price <= 0) {
            price = meta.optDouble("chartPreviousClose", Double.NaN);
        }
        if (Double.isNaN(price) || price <= 0) throw new IllegalStateException("no numeric market price in Yahoo response");

        String currency = meta.optString("currency", "USD");
        long epoch = meta.optLong("regularMarketTime", 0L);
        String timestamp = epoch > 0 ? formatEpoch(epoch) : "";
        String yahooName = meta.optString("symbol", symbol);
        String extra = yahooName.equals(symbol) ? "" : ("Yahoo symbol " + yahooName);
        return new MarketQuote(label + " (" + symbol + ")", formatPrice(price), currency, timestamp, "Yahoo Finance chart API", "https://finance.yahoo.com/quote/" + enc, extra);
    }

    private MarketQuote fetchStooqCsvQuote(String label, String symbol) throws Exception {
        String enc = URLEncoder.encode(symbol, StandardCharsets.UTF_8.name());
        String[] urls = new String[]{
                "https://stooq.com/q/l/?s=" + enc + "&f=sd2t2ohlcv&e=csv",
                "https://stooq.com/q/l/?s=" + enc + "&f=sd2t2ohlcv&h&e=csv",
                "https://stooq.pl/q/l/?s=" + enc + "&f=sd2t2ohlcv&e=csv"
        };
        Exception last = null;
        for (String url : urls) {
            try {
                String csv = downloadText(url, "text/csv,text/plain,*/*", 60000).text;
                String[] lines = csv.split("\r?\n");
                if (lines.length < 2) throw new IllegalStateException("CSV has no data line: " + shorten(csv, 100));
                String line = lines[1];
                if (line.toUpperCase(Locale.US).contains("N/D")) throw new IllegalStateException("CSV returned N/D");
                String[] v = splitCsvOrSemicolon(line);
                if (v.length < 7) throw new IllegalStateException("CSV has unexpected columns: " + shorten(line, 100));
                String ts = clean(v[1] + " " + v[2]);
                String extra = "open " + v[3] + ", high " + v[4] + ", low " + v[5];
                return new MarketQuote(label + " (" + symbol + ")", v[6], "USD", ts, "Stooq CSV", url, extra);
            } catch (Exception e) {
                last = e;
            }
        }
        if (last != null) throw last;
        return null;
    }

    private void fetchPagesAndBuildEvidence(String query, List<SearchItem> items, List<String> debug) {
        List<String> terms = queryTerms(query);
        int opened = 0;
        for (SearchItem item : items) {
            if (opened >= MAX_OPENED_LINKS) break;
            if (!isFetchableUrl(item.url)) continue;
            if (item.source.equals("Structured market quote")) continue;
            if (item.source.equals("Google News RSS")) continue;
            if (!item.chunks.isEmpty() && item.fetchStatus.equals("instant answer")) continue;

            opened++;
            try {
                DownloadResult r = downloadText(item.url, "text/html,application/xhtml+xml,application/xml,text/plain,*/*", MAX_DOWNLOAD_CHARS);
                item.finalUrl = r.finalUrl;
                String ct = r.contentType.toLowerCase(Locale.US);
                if (!ct.isEmpty() && !ct.contains("text") && !ct.contains("html") && !ct.contains("xml")) {
                    item.fetchStatus = "opened but skipped non-text content: " + shorten(ct, 40);
                    continue;
                }

                String html = r.text;
                String pageUrl = item.finalUrl.isEmpty() ? item.url : item.finalUrl;
                String title = firstNonEmpty(stripHtml(extractFirst(html, "(?is)<title[^>]*>(.*?)</title>")), item.title);
                String meta = extractMetaDescription(html);
                item.images.addAll(extractImages(html, pageUrl, title, query, terms));

                String readable = extractReadableText(html, title, meta);
                List<String> chunks = chunkText(readable, 620, 140);
                Set<String> seen = new HashSet<>();
                int added = 0;
                for (String c : chunks) {
                    double score = scoreText(c, item.title + " " + item.snippet, terms, query);
                    if (score < 0.25) continue;
                    String key = normalizeTextKey(c);
                    if (!seen.add(key)) continue;
                    item.chunks.add(new TextChunk(item, c, score));
                    added++;
                    if (added >= 8) break;
                }
                String fallback = clean(item.title + ". " + item.snippet + ". " + meta);
                if (fallback.length() > 30) item.chunks.add(new TextChunk(item, fallback, scoreText(fallback, item.title, terms, query) + 0.2));
                item.fetchStatus = "opened, extracted " + item.chunks.size() + " text chunks and " + item.images.size() + " image candidates";
            } catch (Exception e) {
                item.fetchStatus = "could not open: " + e.getClass().getSimpleName();
                debug.add("open failed for " + shorten(item.url, 60) + ": " + e.getClass().getSimpleName());
            }
        }

        for (SearchItem item : items) {
            if (item.source.equals("Google News RSS") && !item.snippet.isEmpty()) {
                double score = scoreText(item.title + ". " + item.snippet, item.title, terms, query) + 0.3;
                item.chunks.add(new TextChunk(item, item.title + ". " + item.snippet, score));
                item.fetchStatus = "RSS snippet evidence";
            }
        }
    }

    private String extractReadableText(String html, String title, String meta) {
        String body = html == null ? "" : html;
        body = body.replaceAll("(?is)<script\\b.*?</script>", " ")
                .replaceAll("(?is)<style\\b.*?</style>", " ")
                .replaceAll("(?is)<noscript\\b.*?</noscript>", " ")
                .replaceAll("(?is)<svg\\b.*?</svg>", " ")
                .replaceAll("(?is)<form\\b.*?</form>", " ")
                .replaceAll("(?is)<header\\b.*?</header>", " ")
                .replaceAll("(?is)<footer\\b.*?</footer>", " ")
                .replaceAll("(?is)<nav\\b.*?</nav>", " ")
                .replaceAll("(?is)<aside\\b.*?</aside>", " ");
        body = body.replaceAll("(?is)</?(p|div|section|article|li|h1|h2|h3|h4|tr|br)[^>]*>", ". ");
        String plain = stripHtml(body);
        plain = removeBoilerplate(plain);
        return clean(title + ". " + meta + ". " + plain);
    }

    private List<ImageSource> extractImages(String html, String pageUrl, String pageTitle, String query, List<String> terms) {
        List<ImageSource> images = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String meta = extractMetaDescription(html);

        Matcher metaMatcher = Pattern.compile("(?is)<meta\\b([^>]+)>").matcher(html == null ? "" : html);
        while (metaMatcher.find() && images.size() < MAX_TOP_IMAGES) {
            Map<String, String> attrs = parseAttrs(metaMatcher.group(1));
            String key = firstNonEmpty(attrs.get("property"), attrs.get("name")).toLowerCase(Locale.US);
            if (key.equals("og:image") || key.equals("og:image:url") || key.equals("twitter:image") || key.equals("twitter:image:src")) {
                String resolved = resolveUrl(pageUrl, attrs.get("content"));
                if (isUsefulImageUrl(resolved, true) && seen.add(normalizeKeyUrl(resolved))) {
                    String near = clean(pageTitle + ". " + meta);
                    images.add(new ImageSource(resolved, pageUrl, pageTitle, "OpenGraph/Twitter preview image", shorten(near, 320), scoreText(near, pageTitle, terms, query) + 1.1));
                }
            }
        }

        Matcher imgMatcher = Pattern.compile("(?is)<img\\b([^>]+)>").matcher(html == null ? "" : html);
        while (imgMatcher.find() && images.size() < 18) {
            Map<String, String> attrs = parseAttrs(imgMatcher.group(1));
            String src = firstNonEmpty(attrs.get("src"), attrs.get("data-src"), attrs.get("data-original"), attrs.get("data-lazy-src"));
            if (src.isEmpty()) src = firstUrlFromSrcSet(firstNonEmpty(attrs.get("srcset"), attrs.get("data-srcset")));
            String resolved = resolveUrl(pageUrl, src);
            if (!isUsefulImageUrl(resolved, false) || !seen.add(normalizeKeyUrl(resolved))) continue;
            String alt = firstNonEmpty(attrs.get("alt"), attrs.get("title"), attrs.get("aria-label"));
            String nearby = stripHtml(html.substring(Math.max(0, imgMatcher.start() - 450), Math.min(html.length(), imgMatcher.end() + 650)));
            nearby = removeBoilerplate(nearby);
            double score = scoreText(alt + ". " + nearby, pageTitle, terms, query);
            if (score < 0.35 && alt.length() < 8) continue;
            images.add(new ImageSource(resolved, pageUrl, pageTitle, alt, shorten(nearby, 360), score));
        }

        Collections.sort(images, new Comparator<ImageSource>() {
            public int compare(ImageSource a, ImageSource b) { return Double.compare(b.score, a.score); }
        });
        if (images.size() > MAX_TOP_IMAGES) return new ArrayList<>(images.subList(0, MAX_TOP_IMAGES));
        return images;
    }

    private List<TextChunk> rankChunks(List<SearchItem> items) {
        List<TextChunk> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SearchItem item : items) {
            for (TextChunk c : item.chunks) {
                if (c.text.length() < 35) continue;
                if (seen.add(normalizeTextKey(c.text))) all.add(c);
            }
        }
        Collections.sort(all, new Comparator<TextChunk>() {
            public int compare(TextChunk a, TextChunk b) { return Double.compare(b.score, a.score); }
        });
        if (all.size() > MAX_TOP_CHUNKS) return new ArrayList<>(all.subList(0, MAX_TOP_CHUNKS));
        return all;
    }

    private List<ImageSource> rankImages(List<SearchItem> items) {
        List<ImageSource> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SearchItem item : items) {
            for (ImageSource img : item.images) {
                if (seen.add(normalizeKeyUrl(img.imageUrl))) all.add(img);
            }
        }
        Collections.sort(all, new Comparator<ImageSource>() {
            public int compare(ImageSource a, ImageSource b) { return Double.compare(b.score, a.score); }
        });
        if (all.size() > MAX_TOP_IMAGES) return new ArrayList<>(all.subList(0, MAX_TOP_IMAGES));
        return all;
    }

    private void addSnippetFallbackChunks(String query, List<SearchItem> items, List<TextChunk> out) {
        List<String> terms = queryTerms(query);
        for (SearchItem item : items) {
            String evidence = clean(item.title + ". " + item.snippet);
            if (evidence.length() < 30) continue;
            out.add(new TextChunk(item, evidence, scoreText(evidence, item.title, terms, query)));
            if (out.size() >= MAX_TOP_CHUNKS) break;
        }
    }

    private String buildPromptContext(String query, List<TextChunk> chunks, List<ImageSource> images, List<String> debug, String directAnswer) {
        StringBuilder p = new StringBuilder();
        p.append("WEB RAG CONTEXT\nQuery: ").append(shorten(query, 180)).append("\n");
        if (!clean(directAnswer).isEmpty()) {
            p.append("DETERMINISTIC STRUCTURED ANSWER: ").append(shorten(directAnswer, 420)).append("\n");
            p.append("For live price/rate questions, use this structured answer first and cite [S1]. Do not replace it with news snippets.\n");
        }
        p.append("Instructions:\n");
        p.append("- Answer only from the evidence. Cite text as [S1], [S2].\n");
        p.append("- Image evidence [I1] contains image URL + alt/caption/nearby text. Do not say you visually inspected it unless your multimodal model receives the image pixels.\n");
        p.append("- If evidence is weak, blocked, or only from snippets/RSS, say so.\n\n");
        int i = 1;
        for (TextChunk c : chunks) {
            String url = c.item.finalUrl.isEmpty() ? c.item.url : c.item.finalUrl;
            String block = "[S" + i + "] " + shorten(c.item.title.isEmpty() ? "Untitled source" : c.item.title, 100) +
                    " | " + shorten(c.item.source, 30) + " | " + shorten(url, 150) + "\n" +
                    "Evidence: " + shorten(c.text, MAX_SOURCE_CHARS) + "\n\n";
            if (p.length() + block.length() > MAX_PROMPT_CONTEXT_CHARS) break;
            p.append(block);
            i++;
        }
        int j = 1;
        for (ImageSource img : images) {
            String block = "[I" + j + "] " + shorten(img.pageTitle.isEmpty() ? "Image evidence" : img.pageTitle, 95) +
                    " | " + shorten(img.pageUrl, 140) + "\n" +
                    "Image URL: " + shorten(img.imageUrl, 170) + "\n" +
                    "Alt/caption/nearby: " + shorten(clean(img.altText + ". " + img.captionOrNearbyText), 420) + "\n\n";
            if (p.length() + block.length() > MAX_PROMPT_CONTEXT_CHARS) break;
            p.append(block);
            j++;
        }
        if (!debug.isEmpty() && p.length() < MAX_PROMPT_CONTEXT_CHARS - 250) {
            p.append("Retrieval notes: ").append(shorten(join(debug, "; "), 230));
        }
        return p.toString().trim();
    }

    private String buildDisplayText(List<TextChunk> chunks, List<ImageSource> images, List<String> debug, String directAnswer) {
        StringBuilder d = new StringBuilder("Web RAG: structured data → search → open pages → extract text/images → chunk → rank.\n\n");
        if (!clean(directAnswer).isEmpty()) {
            d.append("DIRECT ANSWER: ").append(directAnswer).append("\n\n");
        }
        int i = 1;
        for (TextChunk c : chunks) {
            String url = c.item.finalUrl.isEmpty() ? c.item.url : c.item.finalUrl;
            String title = c.item.title.isEmpty() ? "Untitled source" : c.item.title;
            d.append("[S").append(i).append("] ").append(markdownLink(title, url)).append("\n");
            d.append("   ").append(c.item.source).append(" · ").append(c.item.fetchStatus).append(" · score ").append(String.format(Locale.US, "%.2f", c.score)).append("\n");
            d.append("   ").append(shorten(c.text, 230)).append("\n");
            i++;
        }
        int j = 1;
        for (ImageSource img : images) {
            String title = img.pageTitle.isEmpty() ? "Image evidence" : "Image evidence from " + img.pageTitle;
            d.append("\n[I").append(j).append("] ").append(markdownLink(title, img.pageUrl)).append("\n");
            d.append("   score ").append(String.format(Locale.US, "%.2f", img.score)).append(" · ").append(shorten(clean(img.altText + ". " + img.captionOrNearbyText), 210)).append("\n");
            if (!img.imageUrl.isEmpty()) d.append("   ").append(markdownLink("open image", img.imageUrl)).append("\n");
            j++;
        }
        if (!debug.isEmpty()) d.append("\nNotes: ").append(shorten(join(debug, "; "), 500));
        return d.toString().trim();
    }

    private List<SourceLink> buildSourceLinks(List<TextChunk> chunks, List<ImageSource> images) {
        List<SourceLink> links = new ArrayList<>();
        int i = 1;
        for (TextChunk c : chunks) {
            String url = c.item.finalUrl.isEmpty() ? c.item.url : c.item.finalUrl;
            String title = c.item.title.isEmpty() ? "Untitled source" : c.item.title;
            if (!url.isEmpty()) links.add(new SourceLink("S" + i, title, url, c.item.source));
            i++;
        }
        int j = 1;
        for (ImageSource img : images) {
            String pageUrl = img.pageUrl.isEmpty() ? img.imageUrl : img.pageUrl;
            String title = img.pageTitle.isEmpty() ? "Image evidence" : img.pageTitle;
            if (!pageUrl.isEmpty()) links.add(new SourceLink("I" + j, title, pageUrl, "Image evidence"));
            j++;
        }
        return links;
    }

    private String markdownLink(String label, String url) {
        String cleanLabel = clean(label).replace("[", "(").replace("]", ")");
        String cleanUrl = clean(url);
        if (cleanUrl.isEmpty()) return cleanLabel;
        return "[" + cleanLabel + "](" + cleanUrl + ")";
    }

    private double scoreText(String text, String hint, List<String> terms, String fullQuery) {
        String h = clean(text).toLowerCase(Locale.US);
        String hi = clean(hint).toLowerCase(Locale.US);
        if (h.isEmpty()) return 0;
        double score = 0;
        String q = clean(fullQuery).toLowerCase(Locale.US);
        if (!q.isEmpty() && h.contains(q)) score += 4.0;
        for (String t : terms) {
            int c = countOccurrences(h, t);
            if (c > 0) score += Math.min(3, c);
            if (hi.contains(t)) score += 0.6;
        }
        if (h.matches(".*\\b(19|20)[0-9]{2}\\b.*")) score += 0.25;
        if (h.matches(".*\\b[0-9]+([.,][0-9]+)?\\b.*")) score += 0.15;
        if (h.length() > 170 && h.length() < 900) score += 0.2;
        return score;
    }

    private List<String> chunkText(String text, int targetChars, int overlapChars) {
        String cleaned = clean(text);
        List<String> chunks = new ArrayList<>();
        if (cleaned.isEmpty()) return chunks;
        String[] sentences = cleaned.split("(?<=[.!?])\\s+|\\s{3,}");
        StringBuilder cur = new StringBuilder();
        for (String s0 : sentences) {
            String s = clean(s0);
            if (s.length() < 24) continue;
            if (s.length() > 900) s = s.substring(0, 900);
            if (cur.length() + s.length() + 1 > targetChars && cur.length() > 0) {
                chunks.add(cur.toString().trim());
                String tail = cur.substring(Math.max(0, cur.length() - overlapChars));
                cur.setLength(0);
                cur.append(tail).append(' ');
            }
            cur.append(s).append(' ');
        }
        if (cur.length() > 40) chunks.add(cur.toString().trim());
        if (chunks.isEmpty() && cleaned.length() > 40) chunks.add(cleaned.substring(0, Math.min(cleaned.length(), targetChars)));
        return chunks;
    }

    private List<String> queryTerms(String query) {
        Set<String> stop = new LinkedHashSet<>();
        String[] sw = {"what","when","where","which","with","from","that","this","there","their","latest","current","find","give","show","about","into","using","answer","please","tell","news","today","now","does","have","will","would","could","should","best","good","make","more","less","than","then","also"};
        Collections.addAll(stop, sw);
        List<String> terms = new ArrayList<>();
        String normalized = query.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", " ");
        for (String t : normalized.split("\\s+")) {
            if (t.length() >= 3 && !stop.contains(t) && !terms.contains(t)) terms.add(t);
        }
        return terms;
    }

    private DownloadResult downloadText(String urlText, String accept, int maxChars) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlText).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setRequestMethod("GET");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125 Mobile Safari/537.36 WebRAGBot/2.0");
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        c.setRequestProperty("Connection", "close");
        int code = c.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        if (stream == null) throw new IllegalStateException("HTTP " + code + " with empty response");
        String contentType = c.getContentType() == null ? "" : c.getContentType();
        Charset charset = charsetFromContentType(contentType);
        BufferedReader r = new BufferedReader(new InputStreamReader(stream, charset));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            sb.append(line).append('\n');
            if (sb.length() >= maxChars) break;
        }
        r.close();
        String finalUrl = c.getURL() == null ? urlText : c.getURL().toString();
        c.disconnect();
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + ": " + shorten(sb.toString(), 120));
        return new DownloadResult(sb.toString(), finalUrl, contentType);
    }

    private static Charset charsetFromContentType(String contentType) {
        Matcher m = Pattern.compile("(?i)charset=([a-z0-9_\\-]+)").matcher(contentType == null ? "" : contentType);
        if (m.find()) {
            try { return Charset.forName(m.group(1)); } catch (Exception ignored) { }
        }
        return StandardCharsets.UTF_8;
    }

    private static String extractMetaDescription(String html) {
        Matcher m = Pattern.compile("(?is)<meta\\b([^>]+)>").matcher(html == null ? "" : html);
        while (m.find()) {
            Map<String, String> attrs = parseAttrs(m.group(1));
            String key = firstNonEmpty(attrs.get("name"), attrs.get("property")).toLowerCase(Locale.US);
            if (key.equals("description") || key.equals("og:description") || key.equals("twitter:description")) return stripHtml(attrs.get("content"));
        }
        return "";
    }

    private static Map<String, String> parseAttrs(String text) {
        Map<String, String> attrs = new LinkedHashMap<>();
        if (text == null) return attrs;
        Matcher q = Pattern.compile("([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*([\"'])(.*?)\\2").matcher(text);
        while (q.find()) attrs.put(q.group(1).toLowerCase(Locale.US), decodeHtml(q.group(3)));
        Matcher u = Pattern.compile("([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*([^\\s\"'>]+)").matcher(text);
        while (u.find()) if (!attrs.containsKey(u.group(1).toLowerCase(Locale.US))) attrs.put(u.group(1).toLowerCase(Locale.US), decodeHtml(u.group(2)));
        return attrs;
    }

    private static String firstUrlFromSrcSet(String srcset) {
        if (srcset == null) return "";
        for (String part : srcset.split(",")) {
            String[] bits = clean(part).split("\\s+");
            if (bits.length > 0 && !bits[0].isEmpty()) return bits[0];
        }
        return "";
    }

    private static String resolveUrl(String pageUrl, String src) {
        String s = clean(src);
        if (s.isEmpty() || s.startsWith("data:")) return "";
        try {
            if (s.startsWith("//")) return "https:" + s;
            return new URL(new URL(pageUrl), s).toString();
        } catch (Exception e) {
            return s;
        }
    }

    private static boolean isUsefulImageUrl(String url, boolean allowPreviewNoExt) {
        if (!isFetchableUrl(url)) return false;
        String l = url.toLowerCase(Locale.US);
        if (containsAny(l, "sprite", "favicon", "spacer", "blank.gif")) return false;
        if (containsAny(l, "logo", "icon")) return false;
        if (l.endsWith(".svg") || l.endsWith(".gif")) return false;
        if (allowPreviewNoExt) return true;
        return containsAny(l, ".jpg", ".jpeg", ".png", ".webp", ".avif", "image");
    }

    private static List<SearchItem> dedupe(List<SearchItem> items, int max) {
        List<SearchItem> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (SearchItem item : items) {
            if (item.title.isEmpty() && item.snippet.isEmpty() && item.url.isEmpty()) continue;
            String key = !item.url.isEmpty() ? normalizeKeyUrl(item.url) : item.title.toLowerCase(Locale.US);
            if (seen.add(key)) out.add(item);
            if (out.size() >= max) break;
        }
        return out;
    }

    private static int countLinks(List<SearchItem> items) {
        int n = 0;
        for (SearchItem i : items) if (isFetchableUrl(i.url)) n++;
        return n;
    }

    private static boolean looksCurrentQuestion(String q) {
        String l = q.toLowerCase(Locale.US);
        return containsAny(l, "latest", "today", "now", "current", "news", "price", "stock", "weather", "score", "2026", "2027", "breaking");
    }

    private static String normalizeDuckDuckGoUrl(String href) {
        String v = decodeHtml(clean(href));
        try {
            int idx = v.indexOf("uddg=");
            if (idx >= 0) {
                String encoded = v.substring(idx + 5);
                int amp = encoded.indexOf('&');
                if (amp >= 0) encoded = encoded.substring(0, amp);
                return URLDecoder.decode(encoded, StandardCharsets.UTF_8.name());
            }
            if (v.startsWith("//")) return "https:" + v;
            if (v.startsWith("/")) return "https://duckduckgo.com" + v;
            return v;
        } catch (Exception e) {
            return v;
        }
    }

    private static String normalizeKeyUrl(String url) {
        String k = clean(url).toLowerCase(Locale.US);
        int hash = k.indexOf('#');
        if (hash >= 0) k = k.substring(0, hash);
        while (k.endsWith("/")) k = k.substring(0, k.length() - 1);
        return k;
    }

    private static String normalizeTextKey(String text) {
        String k = clean(text).toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", " ").trim();
        return k.length() > 220 ? k.substring(0, 220) : k;
    }

    private static boolean isFetchableUrl(String url) {
        String l = clean(url).toLowerCase(Locale.US);
        return l.startsWith("http://") || l.startsWith("https://");
    }

    private static String stripHtml(String value) {
        if (value == null) return "";
        String v = value.replaceAll("(?is)<script\\b.*?</script>", " ")
                .replaceAll("(?is)<style\\b.*?</style>", " ")
                .replaceAll("(?is)<!--.*?-->", " ")
                .replaceAll("(?is)<[^>]+>", " ");
        return clean(decodeHtml(v));
    }

    private static String removeBoilerplate(String value) {
        return clean(value).replaceAll("(?i)\\b(accept all cookies|cookie settings|privacy policy|terms of use|subscribe to our newsletter|enable javascript|sign in|log in)\\b", " ").trim();
    }

    private static String decodeHtml(String value) {
        if (value == null) return "";
        String out = value.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&nbsp;", " ").replace("&ndash;", "–").replace("&mdash;", "—");
        Matcher dec = Pattern.compile("&#(\\d+);").matcher(out);
        StringBuffer sb = new StringBuffer();
        while (dec.find()) {
            try { dec.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(Integer.parseInt(dec.group(1)))))); }
            catch (Exception e) { dec.appendReplacement(sb, Matcher.quoteReplacement(dec.group(0))); }
        }
        dec.appendTail(sb);
        Matcher hex = Pattern.compile("&#x([0-9a-fA-F]+);").matcher(sb.toString());
        StringBuffer sb2 = new StringBuffer();
        while (hex.find()) {
            try { hex.appendReplacement(sb2, Matcher.quoteReplacement(new String(Character.toChars(Integer.parseInt(hex.group(1), 16))))); }
            catch (Exception e) { hex.appendReplacement(sb2, Matcher.quoteReplacement(hex.group(0))); }
        }
        hex.appendTail(sb2);
        return sb2.toString();
    }

    private static String firstNonEmpty(String... vals) {
        if (vals == null) return "";
        for (String v : vals) if (v != null && !v.trim().isEmpty()) return v.trim();
        return "";
    }

    private static boolean containsAny(String v, String... needles) {
        if (v == null) return false;
        for (String n : needles) if (v.contains(n)) return true;
        return false;
    }

    private static int countOccurrences(String text, String term) {
        int n = 0, idx = 0;
        while ((idx = text.indexOf(term, idx)) >= 0) { n++; idx += term.length(); }
        return n;
    }

    private static String titleFromText(String text) {
        String c = clean(text);
        int dash = c.indexOf(" - ");
        return shorten(dash > 0 ? c.substring(0, dash) : c, 90);
    }

    private static String extractFirst(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text == null ? "" : text);
        return m.find() ? m.group(1) : "";
    }

    private static String xmlTag(String block, String tag) {
        return stripHtml(extractFirst(block, "(?is)<" + tag + ">(.*?)</" + tag + ">"));
    }

    private static boolean looksLikeNavigation(String text) {
        String l = clean(text).toLowerCase(Locale.US);
        return l.equals("next") || l.equals("previous") || l.equals("more") || l.contains("duckduckgo");
    }

    private static String formatEpoch(long epochSeconds) {
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            return fmt.format(new Date(epochSeconds * 1000L));
        } catch (Exception e) {
            return String.valueOf(epochSeconds);
        }
    }

    private static String formatPrice(double v) {
        if (Math.abs(v) >= 1000) return String.format(Locale.US, "%,.2f", v);
        if (Math.abs(v) >= 10) return String.format(Locale.US, "%.2f", v);
        return String.format(Locale.US, "%.5f", v);
    }

    private static String[] splitCsvOrSemicolon(String line) {
        if (line == null) return new String[0];
        return line.indexOf(';') >= 0 ? line.split(";") : line.split(",");
    }

    private static boolean looksStaleNewsTitle(String title) {
        String t = clean(title).toLowerCase(Locale.US);
        if (t.matches(".*\\b20(1[0-9]|2[0-5])\\b.*")) return true;
        return t.contains("prediction") || t.contains("forecast") || t.contains("key levels") || t.contains("limited upside");
    }

    private static String removeUrls(String text) {
        if (text == null) return "";
        return clean(text.replaceAll("https?://\\S+", " ").replaceAll("www\\.\\S+", " "));
    }

    private static String clean(String v) {
        if (v == null) return "";
        return v.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String shorten(String v, int max) {
        String c = clean(v);
        if (max <= 1 || c.length() <= max) return c;
        return c.substring(0, max - 1) + "…";
    }

    private static String join(List<String> values, String sep) {
        StringBuilder b = new StringBuilder();
        for (String v : values) {
            if (b.length() > 0) b.append(sep);
            b.append(v);
        }
        return b.toString();
    }
}
