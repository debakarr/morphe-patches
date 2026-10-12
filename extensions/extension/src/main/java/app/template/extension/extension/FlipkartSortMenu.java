package app.template.extension.extension;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds "Most rated" and "Top rated" to the Sort tab of Flipkart's own Filters page.
 *
 * <p>The tab is an ATLAS radio group: {@code dlsData.SelectionGroupData_0.value} is a list of
 * cards, each with {@code RadioButtonData_0.value = {id, label}} and a
 * {@code SelectionViewData_0.action} that rewrites the page URL
 * ({@code operationList: [{key: "sort", value: "price_asc", ...}]}). Our two cards copy the
 * "Popularity" card with their own label and sort value; the page request that follows carries
 * {@code sort=morphe_most_rated}, which {@link #onPageUri(String)} turns into the sort mode.
 */
final class FlipkartSortMenu {

    private FlipkartSortMenu() {}

    static final String MOST_RATED_VALUE = "morphe_most_rated";
    static final String TOP_RATED_VALUE = "morphe_top_rated";

    private static final Pattern SORT_PARAM = Pattern.compile("[?&]sort=([A-Za-z0-9_.%-]+)");

    /** True once the user picked one of our entries, so picking another option can turn sorting off. */
    private static volatile boolean sChosenHere;

    /** Adds our cards to every sort radio group in the response. */
    static String rewriteResponse(String json) {
        if (json == null || json.indexOf("\"operationList\"") < 0 || json.indexOf("\"RadioButtonData_0\"") < 0) {
            return json;
        }
        try {
            JSONObject root = new JSONObject(json);
            return addCards(root, 0) ? root.toString() : json;
        } catch (Throwable t) {
            SortByRatingsHelper.log("flipkart sort menu failed: " + t);
            return json;
        }
    }

    private static boolean addCards(Object node, int depth) {
        if (depth > 16 || node == null) return false;
        boolean changed = false;
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            if (isSortGroup(arr) && addTo(arr)) changed = true;
            for (int i = 0; i < arr.length(); i++) {
                if (addCards(arr.opt(i), depth + 1)) changed = true;
            }
        } else if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                if (addCards(obj.opt(keys.next()), depth + 1)) changed = true;
            }
        }
        return changed;
    }

    /** The first operation of a card's selection action, or null. */
    private static JSONObject operation(JSONObject card) {
        JSONObject value = card.optJSONObject("value");
        JSONObject selection = value != null ? value.optJSONObject("SelectionViewData_0") : null;
        JSONObject action = selection != null ? selection.optJSONObject("action") : null;
        JSONObject params = action != null ? action.optJSONObject("params") : null;
        JSONArray ops = params != null ? params.optJSONArray("operationList") : null;
        return ops != null ? ops.optJSONObject(0) : null;
    }

    private static boolean isSortGroup(JSONArray arr) {
        if (arr.length() < 2) return false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject card = arr.optJSONObject(i);
            JSONObject op = card != null ? operation(card) : null;
            if (op == null || !"sort".equals(op.optString("key"))
                || card.optJSONObject("value").optJSONObject("RadioButtonData_0") == null) {
                return false;
            }
        }
        return true;
    }

    private static boolean addTo(JSONArray arr) {
        JSONObject base = null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject card = arr.optJSONObject(i);
            String value = operation(card).optString("value");
            if (MOST_RATED_VALUE.equals(value)) return false; // already added
            if (base == null && "popularity".equals(value)) base = card;
        }
        if (base == null) base = arr.optJSONObject(0);
        SortState.Mode mode = SortState.mode();
        if (mode != SortState.Mode.OFF) {
            for (int i = 0; i < arr.length(); i++) setSelected(arr.optJSONObject(i), false);
        }
        arr.put(copyOf(base, "Most rated", MOST_RATED_VALUE, mode == SortState.Mode.COUNT));
        arr.put(copyOf(base, "Top rated", TOP_RATED_VALUE, mode == SortState.Mode.RATING));
        return true;
    }

    private static void setSelected(JSONObject card, boolean selected) {
        JSONObject value = card != null ? card.optJSONObject("value") : null;
        JSONObject selection = value != null ? value.optJSONObject("SelectionViewData_0") : null;
        JSONObject v = selection != null ? selection.optJSONObject("value") : null;
        put(v, "defaultSelected", selected);
    }

    private static JSONObject copyOf(JSONObject base, String label, String sortValue, boolean selected) {
        try {
            JSONObject card = new JSONObject(base.toString());
            JSONObject value = card.getJSONObject("value");
            String key = "Key-" + label;
            JSONObject radio = value.optJSONObject("RadioButtonData_0");
            JSONObject radioValue = radio != null ? radio.optJSONObject("value") : null;
            put(radioValue, "id", key);
            put(radioValue, "label", label);
            JSONObject selection = value.optJSONObject("SelectionViewData_0");
            JSONObject selectionValue = selection != null ? selection.optJSONObject("value") : null;
            put(selectionValue, "itemKey", key);
            put(selectionValue, "defaultSelected", selected);
            put(operation(card), "value", sortValue);
            retitle(value.optJSONObject("trackerData_0"), label);
            retitle(selection != null ? selection.optJSONObject("action") : null, label);
            return card;
        } catch (Exception e) {
            return base;
        }
    }

    /** Keeps tracking payloads consistent with the new label. */
    private static void retitle(JSONObject holder, String label) {
        JSONObject tracking = holder != null ? holder.optJSONObject("tracking") : null;
        put(tracking, "contentTitle", label);
    }

    private static void put(JSONObject obj, String key, Object value) {
        if (obj == null) return;
        try {
            obj.put(key, value);
        } catch (Exception ignored) {
            // constants
        }
    }

    /**
     * The page URI of a page fetch the app is sending. After the user taps one of the radio
     * cards the page is fetched again with {@code sort=<value>} in its query.
     */
    static void onPageUri(String uri) {
        if (uri == null) return;
        Matcher m = SORT_PARAM.matcher(uri);
        if (!m.find()) return;
        String value = m.group(1);
        if (Diag.ENABLED) SortByRatingsHelper.log("flipkart page uri sort=" + value);
        if (MOST_RATED_VALUE.equals(value)) {
            sChosenHere = true;
            SortState.setMode(SortState.Mode.COUNT);
        } else if (TOP_RATED_VALUE.equals(value)) {
            sChosenHere = true;
            SortState.setMode(SortState.Mode.RATING);
        } else if (sChosenHere) {
            sChosenHere = false;
            SortState.setMode(SortState.Mode.OFF);
        }
    }
}
