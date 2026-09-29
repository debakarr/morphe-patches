package app.template.extension.extension;

import android.util.Log;
import android.webkit.WebView;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * SortByRatingsHelper — client-side "sort by number of ratings" for
 * Amazon and Flipkart search/listing pages.
 *
 * <p>Neither Amazon nor Flipkart exposes a server-side sort by ratings
 * <em>count</em> (Amazon's {@code s=review-rank} sorts by average rating,
 * Flipkart's {@code sort=popularity} is opaque). So this helper only
 * re-orders the product cards already loaded in the DOM, descending by
 * parsed ratings count. Items with no parseable count sort last, original
 * order is preserved for ties and for restore.
 *
 * <p>Limitations, by design:
 * <ul>
 *   <li>Only sorts what is loaded. Infinite scroll / pagination means you
 *       must scroll to load more first, then re-tap sort.</li>
 *   <li>Sponsored / ad cards are skipped, never re-ordered.</li>
 *   <li>Pure DOM reorder, zero network. Breaks if either site changes its
 *       markup — selectors below will need updating.</li>
 * </ul>
 */
public final class SortByRatingsHelper {

    private SortByRatingsHelper() {}

    private static final String TAG = "MorpheSort";
    private static int sNetworkCalls = 0;
    private static int sDiag = 0;
    private static final int MAX_DIAG = 40;

    // Mirror of the ad-card signals used by AmazonHelper so sponsored
    // cards stay where the site put them.
    private static final String[] SPONSORED_HINTS = {
        "AdHolder", "sponsored", "Sponsored", "puis-sponsored-label-text",
        "p13n-sc-sponsored-label", "dynamicSponsoredLabelClass",
        "sbv-ad-content-container",
    };

    private static final String SORT_RATINGS_JS =
        "function(cfg){"
        + "var BTN_ID='morphe-sort-ratings';"
        + "var ADS_BTN_ID='morphe-hide-ads';"
        + "var CARD_SEL=cfg.site==='flipkart'"
        + "? 'div[data-id],div._1AtVbE,div._13oc-S,div._2kHMtA'"
        + ": '[data-component-type=\"s-search-result\"],.s-result-item[data-asin]';"
        + "function isSponsored(el){"
        + "var h=(el.className||'')+' '+(el.getAttribute('cel_widget_id')||'')+' '"
        + "+(el.getAttribute('data-cel-widget')||'')+' '+(el.getAttribute('data-component-type')||'');"
        + "for(var i=0;i<cfg.sponsored.length;i++){if(h.indexOf(cfg.sponsored[i])>=0)return true;}"
        + "if(el.querySelector('.puis-sponsored-label-text,.p13n-sc-sponsored-label,[data-ad-feedback]'))return true;"
        + "return false;}"
        + "function parseCount(text){"
        + "if(!text)return -1;"
        + "var t=text.trim();"
        + "if(!t||/out of/i.test(t))return -1;" // star value like '4.3 out of 5 stars'
        + "var hasWord=/rating|review/i.test(t);"
        + "var paren=/^\\(\\s*[\\d,.]+\\s*[kKmM]?\\s*\\+?\\s*\\)$/.test(t);"
        + "var bare=/^[\\d,.]+\\s*[kKmM]?\\s*\\+?$/.test(t);"
        + "if(!hasWord&&!paren&&!bare)return -1;"
        + "var m=t.match(/([\\d,.]+)\\s*([kKmM])?/);"
        + "if(!m)return -1;"
        + "var n=parseFloat(m[1].replace(/,/g,''));"
        + "if(isNaN(n))return -1;"
        + "var s=(m[2]||'').toLowerCase();"
        + "if(s==='k')n*=1000;else if(s==='m')n*=1000000;"
        + "return Math.floor(n);}"
        + "function amazonCount(card){"
        + "var cands=card.querySelectorAll('a[href*=\"customerReviews\"] span,"
        + "a[href*=\"#customerReviews\"],span.a-size-base.s-underline-text,"
        + "span.a-size-small span,span.a-size-base');"
        + "var star=card.querySelector('[aria-label*=\"out of\"],.a-icon-star');"
        + "var best=-1;"
        + "for(var i=0;i<cands.length;i++){"
        + "var t=cands[i].textContent||'';"
        + "if(t.length>30)continue;"
        + "var v=parseCount(t);"
        + "if(v<0)continue;"
        // Bare numbers only count when the card visibly has stars,
        // otherwise prices / discounts leak in.
        + "if(!/rating|review|\\(/.test(t)&&!star)continue;"
        + "if(v>best)best=v;}"
        + "return best;}"
        + "function flipkartCount(card){"
        + "var cands=card.querySelectorAll('span._2_R_DZ,span');"
        + "for(var i=0;i<cands.length;i++){"
        + "var t=(cands[i].textContent||'').trim();"
        + "if(t.length>16)continue;"
        + "if(!/^\\(\\s*[\\d,]+\\s*\\)$/.test(t))continue;"
        + "var v=parseCount(t);"
        + "if(v>=0)return v;}"
        // Product-page style fallback: '12,345 Ratings & 1,234 Reviews'.
        + "var txt=card.textContent||'';"
        + "var m=txt.match(/([\\d,]+)\\s*Ratings?\\s*&/i);"
        + "if(m)return parseCount(m[1]);"
        + "return -1;}"
        + "function cardCount(card){"
        + "return cfg.site==='flipkart'?flipkartCount(card):amazonCount(card);}"
        + "function collect(){"
        + "var seen=[];var seenSet=new Set();"
        + "var nodes=document.querySelectorAll(CARD_SEL);"
        + "for(var i=0;i<nodes.length;i++){"
        + "var el=nodes[i];"
        + "if(seenSet.has(el)||!el.parentNode||isSponsored(el))continue;"
        // Flipkart anchor fallback can nest; keep the outermost card.
        + "if(el.tagName==='A'&&el.querySelector('div[data-id]'))continue;"
        + "if(cardCount(el)<0&&!(el.textContent||'').match(/\\d/))continue;"
        + "seenSet.add(el);seen.push(el);}"
        + "return seen;}"
        + "function currentParent(cards){"
        + "if(!cards.length)return null;"
        + "var p=cards[0].parentNode;"
        + "for(var i=1;i<cards.length;i++){if(cards[i].parentNode!==p)return null;}"
        + "return p;}"
        + "function applySort(){"
        + "var cards=collect();"
        + "if(cards.length<2)return 0;"
        + "var parent=currentParent(cards);"
        + "if(!parent)return 0;"
        + "if(!parent.getAttribute('data-morphe-orig')){"
        + "var order=[];"
        + "for(var i=0;i<parent.children.length;i++)order.push(parent.children[i]);"
        + "parent.__morpheOrig=order.slice();"
        + "parent.setAttribute('data-morphe-orig','1');}"
        + "var mapped=cards.map(function(el,i){return{el:el,c:cardCount(el),i:i};});"
        + "mapped.sort(function(a,b){if(b.c!==a.c)return b.c-a.c;return a.i-b.i;});"
        + "mapped.forEach(function(m){parent.appendChild(m.el);});"
        + "return mapped.length;}"
        + "function restore(){"
        + "var cards=collect();"
        + "var parent=currentParent(cards);"
        + "if(parent&&parent.__morpheOrig){parent.__morpheOrig.forEach(function(el){parent.appendChild(el);});}}"
        + "function hideAds(){"
        + "var n=0;var nodes=document.querySelectorAll(CARD_SEL);"
        + "for(var i=0;i<nodes.length;i++){var el=nodes[i];"
        + "if(!el.parentNode)continue;"
        + "if(el.getAttribute('data-morphe-ad')==='1')continue;"
        + "if(isSponsored(el)){el.setAttribute('data-morphe-ad','1');el.style.display='none';n++;}}"
        + "return n;}"
        + "function showAds(){"
        + "var nodes=document.querySelectorAll('[data-morphe-ad]');"
        + "for(var i=0;i<nodes.length;i++){nodes[i].style.display='';nodes[i].removeAttribute('data-morphe-ad');}}"
        + "function ensureAdsBtn(){"
        + "var b=document.getElementById(ADS_BTN_ID);"
        + "if(b)return b;"
        + "b=document.createElement('button');b.id=ADS_BTN_ID;b.type='button';"
        + "b.textContent='Remove ads';"
        + "b.setAttribute('data-active','0');"
        + "b.style.cssText='position:fixed;right:12px;bottom:132px;z-index:2147483647;"
        + "padding:10px 14px;border-radius:20px;border:1px solid #888;background:#232f3e;"
        + "color:#fff;font-size:13px;font-weight:bold;box-shadow:0 2px 8px rgba(0,0,0,.4);cursor:pointer';"
        + "b.onclick=function(){"
        + "var on=b.getAttribute('data-active')==='1';"
        + "if(on){showAds();b.setAttribute('data-active','0');b.textContent='Remove ads';}"
        + "else{var n=hideAds();b.setAttribute('data-active','1');"
        + "b.textContent=n>0?'Ads removed \\u2713 ('+n+')':'No ads found';}};"
        + "document.documentElement.appendChild(b);"
        + "return b;}"
        + "function ensureBtn(){"
        + "var b=document.getElementById(BTN_ID);"
        + "if(b)return b;"
        + "b=document.createElement('button');b.id=BTN_ID;b.type='button';"
        + "b.textContent='Sort: Most rated';"
        + "b.setAttribute('data-active','0');"
        + "b.style.cssText='position:fixed;right:12px;bottom:76px;z-index:2147483647;"
        + "padding:10px 14px;border-radius:20px;border:1px solid #888;background:#232f3e;"
        + "color:#fff;font-size:13px;font-weight:bold;box-shadow:0 2px 8px rgba(0,0,0,.4);cursor:pointer';"
        + "b.onclick=function(){"
        + "var on=b.getAttribute('data-active')==='1';"
        + "if(on){b.setAttribute('data-active','0');b.textContent='Sort: Most rated';restore();}"
        + "else{var n=applySort();b.setAttribute('data-active','1');"
        + "b.textContent=n>1?'Sorted ✓ ('+n+') — tap to reset':'No ratings found';}};"
        + "document.documentElement.appendChild(b);"
        + "return b;}"
        + "if(!collect().length)return;"
        + "ensureBtn();ensureAdsBtn();"
        + "if(!window.__morpheSortObs){"
        + "window.__morpheSortObs=true;"
        + "var t=null;var t2=null;"
        + "new MutationObserver(function(){"
        + "var b=document.getElementById(BTN_ID);"
        + "if(b&&b.getAttribute('data-active')==='1'){clearTimeout(t);t=setTimeout(applySort,600);}"
        + "var ab=document.getElementById(ADS_BTN_ID);"
        + "if(ab&&ab.getAttribute('data-active')==='1'){clearTimeout(t2);t2=setTimeout(hideAds,400);}"
        + "}).observe(document.body||document.documentElement,{childList:true,subtree:true});}"
        + "}";

    /**
     * Injects the sort UI on listing pages only. Safe to call on every
     * page load: non-listing pages return early.
     *
     * @param webView the page's WebView
     * @param url     current page URL, used for site + listing detection
     */
    public static void injectSortByRatings(WebView webView, String url) {
        if (webView == null || url == null) return;
        String u = url.trim().toLowerCase();
        String site = null;
        if (u.contains("amazon.")) site = "amazon";
        else if (u.contains("flipkart.com")) site = "flipkart";
        if (site == null) return;

        boolean listing;
        if ("amazon".equals(site)) {
            listing = u.contains("/s?")
                || u.contains("/s/")
                || u.contains("/search/")
                || u.contains("k=")
                || u.contains("rh=");
        } else {
            listing = u.contains("/search")
                || u.contains("q=")
                || u.contains("/category/")
                || u.contains("/browse/");
        }
        if (!listing) return;

        StringBuilder cfg = new StringBuilder("{");
        cfg.append("\"site\":").append(jsonString(site));
        cfg.append(",\"sponsored\":").append(jsonArray(SPONSORED_HINTS));
        cfg.append("}");
        webView.evaluateJavascript("(" + SORT_RATINGS_JS + ")(" + cfg + ");", null);
    }

    /**
     * Processes a Flipkart React Native NetworkCaller JSON response, sorting
     * product entries by {@code ratingCount} descending.
     *
     * <p>Flipkart 9.13+ is a React Native app — search/browse results flow
     * through the {@code NetworkCaller} bridge as raw JSON strings.  This method
     * intercepts the JSON, finds the product map, extracts rating counts, and
     * re-orders the product entries so the JS bundle renders them sorted by
     * number of ratings.
     *
     * <p>The method is defensive: if parsing fails, no product map is found, or
     * fewer than 2 products have parseable rating counts, the original string is
     * returned unchanged.
     *
     * @param json the raw JSON response from the Flipkart API
     * @return sorted JSON string, or the original if sorting is not applicable
     */
    public static String processFlipkartSearchResponse(String json) {
        return processFlipkartResponseJson(json);
    }

    /**
     * Reads a mapi response body in full, re-writes the product maps it
     * contains (sort by rating count, drop ads) and hands Gson a reader over
     * the modified JSON.
     *
     * <p>Hooked on the generic mapi Gson converter, so this covers every page
     * served by mapi: search, category, browse, product page, etc.
     */
    public static java.io.Reader processResponseReader(java.io.Reader reader) {
        if (reader == null) return null;
        try {
            StringBuilder sb = new StringBuilder(16384);
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) >= 0) {
                sb.append(buf, 0, n);
            }
            reader.close();
            String json = sb.toString();
            String processed = processFlipkartResponseJson(json);
            return new java.io.StringReader(processed);
        } catch (Exception e) {
            Log.d(TAG, "reader error: " + e);
            return reader;
        }
    }

    /**
     * Rewrites every {@code "product"} map found anywhere in the JSON:
     * entries are sorted by rating count descending and ad/sponsored entries
     * are dropped.  Everything else is preserved byte-for-byte.
     */
    public static String processFlipkartResponseJson(String json) {
        if (json == null || json.length() < 10) return json;

        if (json.indexOf("\"product\"") < 0) {
            if (sDiag < MAX_DIAG && (json.contains("ratingCount")
                || json.contains("\"ads\"") || json.contains("trackingDataV2"))) {
                sDiag++;
                String keys;
                try {
                    keys = keyList(new JSONObject(json));
                } catch (Exception e) {
                    keys = "(unparsable)";
                }
                Log.d(TAG, "diag#" + sDiag + " len=" + json.length() + " keys=" + keys
                    + " hasRatingCount=" + json.contains("ratingCount")
                    + " hasTracking=" + json.contains("trackingDataV2")
                    + " hasAds=" + json.contains("\"ads\""));
            }
            return json;
        }

        int call = ++sNetworkCalls;
        List<int[]> spans = findAllNamedObjectSpans(json, "product", 0, json.length());
        if (spans.isEmpty()) {
            if (call <= 20) {
                Log.d(TAG, "call#" + call + " has product key but no object span");
            }
            return json;
        }

        int maps = 0;
        int removed = 0;
        // Process back-to-front so edits to a later span cannot invalidate the
        // offsets of the earlier ones.
        for (int i = spans.size() - 1; i >= 0; i--) {
            int[] span = spans.get(i);
            MapResult r = processProductMapObject(json, span, call);
            removed += r.removed;
            if (r.changed) {
                json = json.substring(0, span[0]) + r.text + json.substring(span[1]);
                maps++;
            }
        }

        if (maps > 0) {
            Log.d(TAG, "call#" + call + " REWROTE maps=" + maps
                + " spans=" + spans.size() + " adsRemoved=" + removed);
        }
        return json;
    }

    /** Result of rewriting a single product map object. */
    private static final class MapResult {
        final String text;
        final boolean changed;
        final int removed;

        MapResult(String text, boolean changed, int removed) {
            this.text = text;
            this.changed = changed;
            this.removed = removed;
        }
    }

    private static MapResult processProductMapObject(String json, int[] span, int call) {
        int open = span[0];
        int close = span[1];
        String original = json.substring(open, close);

        List<int[]> rawEntries = splitTopLevelEntries(json, open + 1, close - 1);
        if (rawEntries.size() < 2) return new MapResult(original, false, 0);

        List<RawEntry> entries = new ArrayList<>(rawEntries.size());
        int withRating = 0;
        int adCount = 0;
        for (int[] bounds : rawEntries) {
            String entry = json.substring(bounds[0], bounds[1]).trim();
            int colon = entryColon(entry);
            JSONObject value = null;
            if (colon >= 0) {
                String valueText = entry.substring(colon + 1).trim();
                try {
                    value = new JSONObject(valueText);
                } catch (Exception ignored) {
                    // not an object / not parseable — keep it
                }
            }
            int count = value != null ? extractRatingCount(value) : -1;
            boolean ad = value != null && isAdProduct(value, entry);
            if (count > 0) withRating++;
            if (ad) {
                adCount++;
                if (sDiag < MAX_DIAG) {
                    sDiag++;
                    Log.d(TAG, "ad#" + sDiag + " entry="
                        + entry.substring(0, Math.min(600, entry.length())));
                }
                continue;
            }
            if (sDiag < MAX_DIAG && withRating == 0 && adCount == 0
                && entries.isEmpty() && call <= 30) {
                sDiag++;
                Log.d(TAG, "sample#" + sDiag + " entry="
                    + entry.substring(0, Math.min(1200, entry.length())));
            }
            entries.add(new RawEntry(entry, count));
        }

        boolean sort = withRating >= 2;
        if (sort) {
            Collections.sort(entries, new Comparator<RawEntry>() {
                @Override
                public int compare(RawEntry a, RawEntry b) {
                    return Integer.compare(b.count, a.count);
                }
            });
        }

        if (entries.isEmpty()) {
            // Never delete the whole map.
            return new MapResult(original, false, 0);
        }

        StringBuilder inner = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) inner.append(',');
            inner.append(entries.get(i).raw);
        }
        String text = "{" + inner + "}";
        if (text.equals(original)) return new MapResult(original, false, adCount);
        return new MapResult(text, true, adCount);
    }

    /**
     * Heuristic ad detection for a product map entry.  Works on the entry text
     * as well as the parsed value so it does not depend on the exact model.
     */
    private static boolean isAdProduct(JSONObject value, String rawEntry) {
        // Direct boolean flags / id fields on the wrapper or value.
        if (value.optBoolean("isAd", false)
            || value.optBoolean("ad", false)
            || value.optBoolean("sponsored", false)
            || value.optBoolean("isSponsored", false)
            || value.has("adId")
            || value.has("adData")
            || value.has("adInfo")
            || value.has("adBadge")) {
            return true;
        }
        JSONObject v = value.optJSONObject("value");
        if (v != null && v != value) {
            if (v.optBoolean("isAd", false)
                || v.optBoolean("ad", false)
                || v.optBoolean("sponsored", false)
                || v.optBoolean("isSponsored", false)
                || v.has("adId")
                || v.has("adData")
                || v.has("adInfo")
                || v.has("adBadge")) {
                return true;
            }
            JSONObject tracking = v.optJSONObject("trackingDataV2");
            if (tracking != null) {
                String finding = tracking.optString("findingMethod", "");
                if (finding.contains("ad") || finding.contains("Ad")
                    || finding.contains("sponsor") || finding.contains("Sponsor")) {
                    return true;
                }
            }
        }
        // Raw text fallback: explicit ad markers anywhere in the entry.
        return rawEntry.contains("\"isAd\":true")
            || rawEntry.contains("\"isSponsored\":true")
            || rawEntry.contains("\"sponsored\":true")
            || rawEntry.contains("\"adId\"");
    }

    /** Finds the key/value colon of an object entry, outside of strings. */
    private static int entryColon(String entry) {
        int i = skipWs(entry, 0);
        if (i >= entry.length() || entry.charAt(i) != '"') return -1;
        int keyEnd = skipString(entry, i);
        if (keyEnd < 0) return -1;
        int j = skipWs(entry, keyEnd);
        if (j >= entry.length() || entry.charAt(j) != ':') return -1;
        return j;
    }

    private static String keyList(JSONObject obj) {
        StringBuilder sb = new StringBuilder("[");
        Iterator<String> keys = obj.keys();
        int i = 0;
        while (keys.hasNext() && i < 15) {
            if (i > 0) sb.append(',');
            sb.append(keys.next());
            i++;
        }
        if (keys.hasNext()) sb.append(",...");
        return sb.append(']').toString();
    }

    private static String topKeys(List<RawEntry> entries, int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Math.min(n, entries.size()); i++) {
            if (i > 0) sb.append(',');
            sb.append(entries.get(i).count);
        }
        return sb.append(']').toString();
    }

    private static final class RawEntry {
        final String raw;
        final int count;

        RawEntry(String raw, int count) {
            this.raw = raw;
            this.count = count;
        }
    }

    // ---- Order-preserving raw JSON scanners (string/escape aware) ----

    private static int skipWs(String s, int i) {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
            else break;
        }
        return i;
    }

    /** s.charAt(i) must be '"'; returns index one past the closing quote, or -1. */
    private static int skipString(String s, int i) {
        i++;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '"') return i + 1;
            i++;
        }
        return -1;
    }

    /** Returns index one past the JSON value starting at i, or -1. */
    private static int skipValue(String s, int i) {
        i = skipWs(s, i);
        if (i >= s.length()) return -1;
        char c = s.charAt(i);
        if (c == '"') return skipString(s, i);
        if (c == '{' || c == '[') {
            char open = c;
            char close = c == '{' ? '}' : ']';
            int depth = 0;
            while (i < s.length()) {
                char d = s.charAt(i);
                if (d == '"') {
                    i = skipString(s, i);
                    if (i < 0) return -1;
                    continue;
                }
                if (d == open) depth++;
                else if (d == close) {
                    depth--;
                    if (depth == 0) return i + 1;
                }
                i++;
            }
            return -1;
        }
        while (i < s.length()) {
            char d = s.charAt(i);
            if (d == ',' || d == '}' || d == ']' || d == ' '
                || d == '\t' || d == '\n' || d == '\r') break;
            i++;
        }
        return i;
    }

    /**
     * Finds the span of a named object value at the root level.
     * @return {indexOfOpenBrace, indexOnePastCloseBrace}, or null.
     */
    private static int[] findNamedObjectSpan(String s, String name) {
        int i = skipWs(s, 0);
        if (i >= s.length() || s.charAt(i) != '{') return null;
        i++;
        while (true) {
            i = skipWs(s, i);
            if (i >= s.length() || s.charAt(i) != '"') return null;
            int keyEnd = skipString(s, i);
            if (keyEnd < 0) return null;
            String key = unescape(s.substring(i + 1, keyEnd - 1));
            i = skipWs(s, keyEnd);
            if (i >= s.length() || s.charAt(i) != ':') return null;
            i = skipWs(s, i + 1);
            if (name.equals(key)) {
                if (i < s.length() && s.charAt(i) == '{') {
                    int end = skipValue(s, i);
                    if (end < 0) return null;
                    return new int[]{i, end};
                }
                return null;
            }
            i = skipValue(s, i);
            if (i < 0) return null;
            i = skipWs(s, i);
            if (i < s.length() && s.charAt(i) == ',') {
                i++;
                continue;
            }
            return null;
        }
    }

    /**
     * Finds the spans of every object value stored under {@code name}, at any
     * depth.  Does not descend into a matched object (a nested product map
     * inside another product map is not something Flipkart emits, and nesting
     * would make in-place offsets invalid).
     */
    private static List<int[]> findAllNamedObjectSpans(String s, String name, int from, int to) {
        List<int[]> out = new ArrayList<>();
        collectSpans(s, name, from, Math.min(to, s.length()), out, 0);
        return out;
    }

    private static void collectSpans(String s, String name, int from, int to,
                                     List<int[]> out, int depth) {
        if (depth > 12) return;
        int i = skipWs(s, from);
        while (i < to) {
            char c = s.charAt(i);
            if (c == '"') {
                int keyEnd = skipString(s, i);
                if (keyEnd < 0) return;
                String key = unescape(s.substring(i + 1, keyEnd - 1));
                int j = skipWs(s, keyEnd);
                if (j >= to || s.charAt(j) != ':') {
                    i = keyEnd;
                    continue;
                }
                j = skipWs(s, j + 1);
                if (j >= to) return;
                char v = s.charAt(j);
                if (v == '{' || v == '[') {
                    int end = skipValue(s, j);
                    if (end < 0 || end > to) return;
                    if (name.equals(key) && v == '{') {
                        out.add(new int[]{j, end});
                        // do not descend into a matched map
                    } else {
                        collectSpans(s, name, j + 1, end - 1, out, depth + 1);
                    }
                    i = end;
                    continue;
                }
                int end = skipValue(s, j);
                if (end < 0 || end > to) return;
                i = end;
                continue;
            }
            if (c == '{' || c == '[') {
                int end = skipValue(s, i);
                if (end < 0 || end > to) return;
                collectSpans(s, name, i + 1, end - 1, out, depth + 1);
                i = end;
                continue;
            }
            i++;
        }
    }

    /** Splits [from, to) into top-level comma-separated entry spans. */
    private static List<int[]> splitTopLevelEntries(String s, int from, int to) {
        List<int[]> out = new ArrayList<>();
        int i = skipWs(s, from);
        int entryStart = i;
        boolean empty = true;
        while (i < to) {
            char c = s.charAt(i);
            if (c == '"') {
                i = skipString(s, i);
                if (i < 0) return out;
                continue;
            }
            if (c == '{' || c == '[') {
                i = skipValue(s, i);
                if (i < 0 || i > to) return out;
                continue;
            }
            if (c == ',') {
                out.add(new int[]{entryStart, i});
                i = skipWs(s, i + 1);
                entryStart = i;
                empty = true;
                continue;
            }
            empty = false;
            i++;
        }
        if (!empty || !out.isEmpty()) {
            int end = i;
            while (end > entryStart && Character.isWhitespace(s.charAt(end - 1))) end--;
            if (end > entryStart) out.add(new int[]{entryStart, end});
        }
        return out;
    }

    /** Extracts the key string of a "key":value entry, or null. */
    private static String entryKey(String entry) {
        int i = 0;
        while (i < entry.length() && Character.isWhitespace(entry.charAt(i))) i++;
        if (i >= entry.length() || entry.charAt(i) != '"') return null;
        int keyEnd = skipString(entry, i);
        if (keyEnd < 0) return null;
        return unescape(entry.substring(i + 1, keyEnd - 1));
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                switch (n) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    default: sb.append(n); break;
                }
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Extracts the rating count from a Flipkart product JSON object.
     *
     * <p>Tries multiple known paths:
     * <ol>
     *   <li>{@code trackingDataV2.ratingCount} — the TrackingDataV2 Parcelable field</li>
     *   <li>{@code value.productInfo.trackingDataV2.ratingCount} — nested under value</li>
     *   <li>{@code value.productInfo.rating.totalRatingCount} — alternative rating field</li>
     * </ol>
     */
    private static int extractRatingCount(JSONObject product) {
        // Path 1: direct trackingDataV2.ratingCount
        int count = nestedInt(product, "trackingDataV2", "ratingCount");
        if (count > 0) return count;

        // Path 2: value.productInfo.trackingDataV2.ratingCount
        JSONObject value = product.optJSONObject("value");
        if (value != null) {
            JSONObject productInfo = value.optJSONObject("productInfo");
            if (productInfo != null) {
                count = nestedInt(productInfo, "trackingDataV2", "ratingCount");
                if (count > 0) return count;

                // Path 3: value.productInfo.rating.totalRatingCount
                JSONObject rating = productInfo.optJSONObject("rating");
                if (rating != null) {
                    count = rating.optInt("totalRatingCount", 0);
                    if (count > 0) return count;
                    count = rating.optInt("ratingCount", 0);
                    if (count > 0) return count;
                }
            }

            // Path 4: discovery ProductVInfo value.rating.count
            JSONObject rating = value.optJSONObject("rating");
            if (rating != null) {
                count = rating.optInt("count", 0);
                if (count > 0) return count;
                count = rating.optInt("totalRatingCount", 0);
                if (count > 0) return count;
            }

            // Path 5: discovery ProductVInfo value.count
            count = value.optInt("count", 0);
            if (count > 0) return count;

            // Path 6: value.ratingCount
            count = value.optInt("ratingCount", 0);
            if (count > 0) return count;
        }

        // Path 7: direct rating.count / count on the wrapper
        JSONObject rating = product.optJSONObject("rating");
        if (rating != null) {
            count = rating.optInt("count", 0);
            if (count > 0) return count;
        }
        count = product.optInt("count", 0);
        if (count > 0) return count;

        // Path 8: scan for any "ratingCount" key (defensive fallback)
        count = findIntField(product, "ratingCount");
        return count;
    }

    private static int nestedInt(JSONObject obj, String child, String field) {
        JSONObject c = obj.optJSONObject(child);
        return c != null ? c.optInt(field, 0) : 0;
    }

    /** Recursively searches for an int field by name (max depth 3). */
    private static int findIntField(JSONObject obj, String fieldName) {
        return findIntField(obj, fieldName, 0);
    }

    private static int findIntField(JSONObject obj, String fieldName, int depth) {
        if (depth > 3 || obj == null) return 0;
        if (obj.has(fieldName)) {
            return obj.optInt(fieldName, 0);
        }
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object val = obj.opt(key);
            if (val instanceof JSONObject) {
                int result = findIntField((JSONObject) val, fieldName, depth + 1);
                if (result > 0) return result;
            }
        }
        return 0;
    }

    private static final class ProductEntry {
        final String key;
        final JSONObject product;
        final int ratingCount;

        ProductEntry(String key, JSONObject product, int ratingCount) {
            this.key = key;
            this.product = product;
            this.ratingCount = ratingCount;
        }
    }

    private static String jsonArray(String[] values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(jsonString(values[i]));
        }
        return sb.append("]").toString();
    }

    private static String jsonString(String value) {
        if (value == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20 || c > 0x7e || c == '<' || c == '>' || c == '&'
                        || c == '\'' || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append("\"").toString();
    }
}
