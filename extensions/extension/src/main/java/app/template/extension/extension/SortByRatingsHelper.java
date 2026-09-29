package app.template.extension.extension;

import android.util.Log;
import android.webkit.WebView;

import org.json.JSONArray;
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
    private static int sAdsRemoved = 0;

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
     * Rewrites every product listing found in the JSON:
     * <ul>
     *   <li>new ATLAS feed rows ({@code RESPONSE.slots}) — sorted by rating
     *       count descending, ad cards removed, empty rows dropped;</li>
     *   <li>legacy {@code "product"} maps — same treatment.</li>
     * </ul>
     * Everything else is preserved.
     */
    public static String processFlipkartResponseJson(String json) {
        if (json == null || json.length() < 10) return json;

        // New ATLAS search/browse feed: products live in rows
        // (RESPONSE.slots[].widget.data.dlsData.horizontalListData_0.value),
        // each card carrying ratingData_0.reviewText ("| 4.7K+") and an ad
        // badge in tagData_0 ("AD" / "Sponsored").
        if (json.indexOf("\"horizontalListData_0\"") >= 0
            && json.indexOf("\"ratingData_0\"") >= 0) {
            sAdsRemoved = 0;
            String rewritten = rewriteAtlasSlots(json);
            if (rewritten != null && rewritten != json) {
                Log.d(TAG, "ATLAS feed rewritten: removed " + sAdsRemoved
                    + " ad card(s)");
                json = rewritten;
            }
        }

        if (json.indexOf("\"product\"") < 0) {
            return json;
        }

        // Legacy product map (RN NetworkCaller wrapper).
        int call = ++sNetworkCalls;
        List<int[]> spans = findAllNamedObjectSpans(json, "product", 0, json.length());
        if (spans.isEmpty()) return json;

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
                continue;
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

    // ----------------------------------------------------------------
    // ATLAS search/browse feed (slots + product rows)
    // ----------------------------------------------------------------

    /** One product card in an ATLAS product row. */
    private static final class AtlasCard {
        final Object item;
        final int count;
        final boolean ad;

        AtlasCard(Object item, int count, boolean ad) {
            this.item = item;
            this.count = count;
            this.ad = ad;
        }
    }

    /** One ATLAS row: a slot whose dlsData contains a list of product cards. */
    private static final class AtlasRow {
        final int slotIndex;
        final JSONObject listHolder;
        final JSONArray cards;
        final int originalLen;
        final String type;
        int newLen;

        AtlasRow(int slotIndex, JSONObject listHolder, JSONArray cards, String type) {
            this.slotIndex = slotIndex;
            this.listHolder = listHolder;
            this.cards = cards;
            this.originalLen = cards.length();
            this.type = type;
            this.newLen = this.originalLen;
        }
    }

    /**
     * Rewrites every {@code "slots"} array in the response: product rows are
     * sorted by rating count descending and ad cards are dropped.  Returns the
     * new JSON, or null when nothing changed / parsing failed.
     */
    private static String rewriteAtlasSlots(String json) {
        List<int[]> spans = new ArrayList<>();
        collectArraySpans(json, "slots", 0, json.length(), spans, 0);
        if (spans.isEmpty()) return null;
        boolean changed = false;
        for (int i = spans.size() - 1; i >= 0; i--) {
            int[] span = spans.get(i);
            try {
                JSONArray slots = new JSONArray(json.substring(span[0], span[1]));
                if (rewriteSlotsArray(slots)) {
                    json = json.substring(0, span[0]) + slots + json.substring(span[1]);
                    changed = true;
                }
            } catch (Exception e) {
                Log.d(TAG, "atlas slots error: " + e);
            }
        }
        return changed ? json : null;
    }

    private static boolean rewriteSlotsArray(JSONArray slots) {
        List<AtlasRow> rows = new ArrayList<>();
        for (int i = 0; i < slots.length(); i++) {
            JSONObject slot = slots.optJSONObject(i);
            if (slot == null) continue;
            AtlasRow row = toAtlasRow(slot, i);
            if (row != null) rows.add(row);
        }
        if (rows.isEmpty()) return false;

        boolean changed = false;
        // Group ALL product rows by view type (they may be interleaved with
        // other widgets) so sorting is global across the page.
        java.util.LinkedHashMap<String, List<AtlasRow>> groups =
            new java.util.LinkedHashMap<String, List<AtlasRow>>();
        for (AtlasRow row : rows) {
            List<AtlasRow> group = groups.get(row.type);
            if (group == null) {
                group = new ArrayList<AtlasRow>();
                groups.put(row.type, group);
            }
            group.add(row);
        }
        for (List<AtlasRow> group : groups.values()) {
            if (rewriteRowGroup(group)) changed = true;
        }

        // Rebuild the slot list, dropping ad widgets (header badge "AD" /
        // "Sponsored") and product rows that ended up empty.
        JSONArray kept = new JSONArray();
        boolean removed = false;
        for (int i = 0; i < slots.length(); i++) {
            JSONObject slot = slots.optJSONObject(i);
            if (slot == null) continue;
            if (isAdWidget(slot)) {
                removed = true;
                continue;
            }
            boolean empty = false;
            for (AtlasRow row : rows) {
                if (row.slotIndex == i && row.newLen == 0 && row.originalLen > 0) {
                    empty = true;
                    break;
                }
            }
            if (empty) {
                removed = true;
                continue;
            }
            kept.put(slot);
        }
        if (removed) {
            for (int i = slots.length() - 1; i >= 0; i--) {
                slots.remove(i);
            }
            for (int i = 0; i < kept.length(); i++) {
                slots.put(kept.opt(i));
            }
        }
        return changed || removed;
    }

    /**
     * True for whole widgets that are ads — e.g. the "Top rated products"
     * carousel which carries a header badge with the text "AD".
     */
    private static boolean isAdWidget(JSONObject slot) {
        JSONObject widget = slot.optJSONObject("widget");
        if (widget == null) return false;
        JSONObject data = widget.optJSONObject("data");
        if (data == null) return false;
        JSONObject dls = data.optJSONObject("dlsData");
        if (dls == null) return false;
        JSONObject header = dls.optJSONObject("header-2-line-content-container_0");
        if (header == null) return false;
        JSONObject value = header.optJSONObject("value");
        if (value == null) return false;
        for (String key : new String[]{"label_0", "label_1", "label_2", "label_3",
            "label_4", "label_5"}) {
            JSONObject label = value.optJSONObject(key);
            if (label == null) continue;
            JSONObject labelValue = label.optJSONObject("value");
            if (labelValue != null && isAdText(labelValue.optString("text", ""))) {
                return true;
            }
        }
        return false;
    }

    private static AtlasRow toAtlasRow(JSONObject slot, int slotIndex) {
        JSONObject widget = slot.optJSONObject("widget");
        if (widget == null) return null;
        JSONObject data = widget.optJSONObject("data");
        if (data == null) return null;
        JSONObject dls = data.optJSONObject("dlsData");
        if (dls == null) return null;
        JSONObject holder = dls.optJSONObject("horizontalListData_0");
        if (holder == null) return null;
        JSONArray cards = holder.optJSONArray("value");
        if (cards == null || cards.length() == 0) return null;
        JSONObject first = cards.optJSONObject(0);
        JSONObject firstValue = first != null ? first.optJSONObject("value") : null;
        if (firstValue == null) return null;
        String type = cardViewType(firstValue);
        if (!isProductRowType(type)) return null;
        return new AtlasRow(slotIndex, holder, cards, type);
    }

    /** True for main feed product card rows (not filters / carousels). */
    private static boolean isProductRowType(String type) {
        if (type == null) return false;
        String t = type.toLowerCase();
        return t.contains("product_summary")
            || t.contains("productcardlist")
            || t.contains("search_feed_card");
    }

    private static boolean rewriteRowGroup(List<AtlasRow> group) {
        List<AtlasCard> all = new ArrayList<>();
        List<Object> before = new ArrayList<>();
        for (AtlasRow row : group) {
            for (int i = 0; i < row.cards.length(); i++) {
                Object item = row.cards.opt(i);
                before.add(item);
                JSONObject card = item instanceof JSONObject
                    ? ((JSONObject) item).optJSONObject("value") : null;
                if (card == null) {
                    all.add(new AtlasCard(item, -1, false));
                    continue;
                }
                all.add(new AtlasCard(item, cardRatingCount(card), isAdCard(card)));
            }
        }
        if (all.size() < 2) return false;

        List<AtlasCard> kept = new ArrayList<>();
        for (AtlasCard card : all) {
            if (!card.ad) kept.add(card);
        }
        boolean adsRemoved = kept.size() != all.size();
        if (adsRemoved) sAdsRemoved += all.size() - kept.size();

        Collections.sort(kept, new Comparator<AtlasCard>() {
            @Override
            public int compare(AtlasCard a, AtlasCard b) {
                return Integer.compare(b.count, a.count);
            }
        });

        List<Object> after = new ArrayList<>();
        for (AtlasCard card : kept) after.add(card.item);

        // Redistribute keeping every row's original size.
        int p = 0;
        for (AtlasRow row : group) {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < row.originalLen && p < kept.size(); i++, p++) {
                arr.put(kept.get(p).item);
            }
            row.newLen = arr.length();
            try {
                row.listHolder.put("value", arr);
            } catch (Exception e) {
                Log.d(TAG, "atlas row write error: " + e);
                return false;
            }
        }

        boolean orderChanged = !sameSequence(before, after);
        return adsRemoved || orderChanged;
    }

    private static boolean sameSequence(List<Object> a, List<Object> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) return false;
        }
        return true;
    }

    private static String cardViewType(JSONObject card) {
        JSONObject tracker = card.optJSONObject("trackerData_0");
        JSONObject tracking = tracker != null ? tracker.optJSONObject("tracking") : null;
        if (tracking == null) return "";
        String view = tracking.optString("viewType", "");
        if (view.isEmpty()) view = tracking.optString("dataKey", "");
        return view;
    }

    /**
     * Rating count shown as {@code ratingData_0.value.reviewText}, e.g.
     * "| 4.7K+", "| 104", "| 1.1L" (lakh).  Returns -1 when absent.
     */
    private static int cardRatingCount(JSONObject card) {
        JSONObject rating = card.optJSONObject("ratingData_0");
        JSONObject value = rating != null ? rating.optJSONObject("value") : null;
        if (value != null) {
            int direct = value.optInt("count", 0);
            if (direct <= 0) direct = value.optInt("ratingCount", 0);
            if (direct > 0) return direct;
            String review = value.optString("reviewText", "");
            int parsed = parseCountText(review);
            if (parsed > 0) return parsed;
        }
        return parseCountText(card.optString("reviewText", ""));
    }

    /** Parses "| 44.3K+", "44.3K", "1,234" into an integer, or -1. */
    private static int parseCountText(String text) {
        if (text == null) return -1;
        String t = text.trim();
        int bar = t.indexOf('|');
        if (bar >= 0) t = t.substring(bar + 1).trim();
        t = t.replace("+", "").replace(",", "").trim();
        if (t.isEmpty()) return -1;
        double mult = 1;
        char last = Character.toLowerCase(t.charAt(t.length() - 1));
        if (last == 'k') {
            mult = 1000;
            t = t.substring(0, t.length() - 1).trim();
        } else if (last == 'm') {
            mult = 1000000;
            t = t.substring(0, t.length() - 1).trim();
        } else if (last == 'l') {
            mult = 100000;
            t = t.substring(0, t.length() - 1).trim();
        } else if (last == 'c') {
            mult = 10000000;
            t = t.substring(0, t.length() - 1).trim();
        }
        if (t.isEmpty()) return -1;
        try {
            double d = Double.parseDouble(t);
            if (d <= 0) return -1;
            return (int) Math.min(Integer.MAX_VALUE, d * mult);
        } catch (Exception e) {
            return -1;
        }
    }

    /** True when the card carries an ad/sponsored badge. */
    private static boolean isAdCard(JSONObject card) {
        if (isAdText(tagText(card, "tagData_0")) || isAdText(tagText(card, "tagData_1"))) {
            return true;
        }
        if (card.optBoolean("isAd", false) || card.optBoolean("isSponsored", false)) {
            return true;
        }
        JSONObject tracking = card.optJSONObject("trackerData_0");
        JSONObject t = tracking != null ? tracking.optJSONObject("tracking") : null;
        if (t != null) {
            String category = t.optString("widgetCategory", "");
            String finding = t.optString("detailedFindingMethod", "");
            if ("AD".equalsIgnoreCase(category)) return true;
            if (finding != null && finding.toLowerCase().contains("sponsor")) return true;
        }
        return false;
    }

    private static String tagText(JSONObject card, String tagKey) {
        JSONObject tag = card.optJSONObject(tagKey);
        if (tag == null) return "";
        JSONObject value = tag.optJSONObject("value");
        return value != null ? value.optString("text", "") : "";
    }

    private static boolean isAdText(String text) {
        if (text == null) return false;
        String t = text.trim();
        return t.equalsIgnoreCase("AD")
            || t.equalsIgnoreCase("Sponsored")
            || t.equalsIgnoreCase("Sponsored Ad");
    }

    /** Finds the spans of every array value stored under {@code name}. */
    private static void collectArraySpans(String s, String name, int from, int to,
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
                if (v == '[') {
                    int end = skipValue(s, j);
                    if (end < 0 || end > to) return;
                    if (name.equals(key)) {
                        out.add(new int[]{j, end});
                    } else {
                        collectArraySpans(s, name, j + 1, end - 1, out, depth + 1);
                    }
                    i = end;
                    continue;
                }
                if (v == '{') {
                    int end = skipValue(s, j);
                    if (end < 0 || end > to) return;
                    collectArraySpans(s, name, j + 1, end - 1, out, depth + 1);
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
                collectArraySpans(s, name, i + 1, end - 1, out, depth + 1);
                i = end;
                continue;
            }
            i++;
        }
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
