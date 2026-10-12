package app.template.extension.extension;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds "Most rated" and "Top rated" to Myntra's own Sort sheet.
 *
 * <p>The sheet is server-driven: every search page layout carries
 * {@code ...halfcardData.filterOptions = [{id, name, isApplied, onHalfcardClick}, ...]}.
 * Our entries are copies of the server's "Popularity" option (a valid sort) with their
 * own id and name; the app's request for the page is reported by
 * {@link #onPageRequest(Object)} so the chosen entry can switch sorting on.
 */
final class MyntraSortMenu {

    private MyntraSortMenu() {}

    static final String MOST_RATED_ID = "morphe_most_rated";
    static final String TOP_RATED_ID = "morphe_top_rated";

    /** Adds our entries to every filterOptions list that has a "popularity" option. */
    static boolean addOptions(Object node, int depth) {
        if (depth > 14 || node == null) return false;
        boolean changed = false;
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            JSONArray options = obj.optJSONArray("filterOptions");
            if (options != null && addTo(options)) changed = true;
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                if (addOptions(obj.opt(keys.next()), depth + 1)) changed = true;
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                if (addOptions(arr.opt(i), depth + 1)) changed = true;
            }
        }
        return changed;
    }

    private static boolean addTo(JSONArray options) {
        JSONObject base = null;
        for (int i = 0; i < options.length(); i++) {
            JSONObject option = options.optJSONObject(i);
            if (option == null) continue;
            if (MOST_RATED_ID.equals(option.optString("id"))) return false; // already added
            if ("popularity".equals(option.optString("id"))) base = option;
        }
        if (base == null || base.optJSONObject("onHalfcardClick") == null) return false;
        SortState.Mode mode = SortState.mode();
        if (mode != SortState.Mode.OFF) {
            for (int i = 0; i < options.length(); i++) {
                JSONObject option = options.optJSONObject(i);
                if (option != null) put(option, "isApplied", false);
            }
        }
        options.put(copyOf(base, MOST_RATED_ID, "Most rated", mode == SortState.Mode.COUNT));
        options.put(copyOf(base, TOP_RATED_ID, "Top rated", mode == SortState.Mode.RATING));
        return true;
    }

    private static JSONObject copyOf(JSONObject base, String id, String name, boolean applied) {
        try {
            JSONObject copy = new JSONObject(base.toString());
            copy.put("id", id);
            copy.put("name", name);
            copy.put("isApplied", applied);
            JSONObject widget = copy.optJSONObject("onHalfcardClick");
            if (widget != null) {
                JSONObject tracking = widget.optJSONObject("tracking");
                JSONObject override = tracking != null ? tracking.optJSONObject("dataOverride") : null;
                JSONObject w = override != null ? override.optJSONObject("widget") : null;
                if (w != null) w.put("name", name);
            }
            return copy;
        } catch (Exception e) {
            return base;
        }
    }

    private static void put(JSONObject obj, String key, Object value) {
        try {
            obj.put(key, value);
        } catch (Exception ignored) {
            // constants
        }
    }

    private static final Pattern SORT_PARAM = Pattern.compile("[?&]sort=([A-Za-z0-9_.%-]+)");

    /** True once the user picked one of our entries, so picking another option can turn sorting off. */
    private static volatile boolean sChosenHere;

    /**
     * The layout-engine page request the app is sending. The app's JavaScript appends the
     * chosen option's {@code id} as {@code ?sort=<id>} to the page URI, so our entries show up
     * as {@code sort=morphe_most_rated} / {@code sort=morphe_top_rated}. The server falls back
     * to its default order for an id it does not know; the reply is then sorted client-side.
     * Requests without a sort parameter (paging, other pages) change nothing.
     */
    static void onPageRequest(Object request) {
        if (request == null) return;
        String text = String.valueOf(request);
        if (Diag.ENABLED) SortByRatingsHelper.log("myntra request: " + text);
        Matcher m = SORT_PARAM.matcher(text);
        if (!m.find()) return;
        String id = m.group(1);
        if (MOST_RATED_ID.equals(id)) {
            sChosenHere = true;
            SortState.setMode(SortState.Mode.COUNT);
        } else if (TOP_RATED_ID.equals(id)) {
            sChosenHere = true;
            SortState.setMode(SortState.Mode.RATING);
        } else if (sChosenHere) {
            sChosenHere = false;
            SortState.setMode(SortState.Mode.OFF);
        }
    }
}
