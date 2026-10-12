package app.template.extension.extension;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds "Most rated" and "Top rated" to Meesho's own Sort sheet.
 *
 * <p>Meesho's sort options are server-driven: every sort-filter response has
 * {@code sort_options: [{selected, display_name, sort_by, sort_order}, ...]}, and the
 * chosen option is written back verbatim into the next listing request. The server
 * knows nothing about our two entries, so they carry the <em>first</em> option's real
 * {@code sort_by} / {@code sort_order} (relevance) and differ only in {@code display_name}.
 * The request stays valid; the app's reply is then sorted client-side like every other
 * listing, and the request writer reports which entry was chosen.
 */
final class MeeshoSortMenu {

    private MeeshoSortMenu() {}

    static final String MOST_RATED = "Most rated";
    static final String TOP_RATED = "Top rated";

    private static final Pattern WRITTEN =
        Pattern.compile("selected=(true|false), name=(.*), sortBy=");

    /** True once the user picked one of our entries, so picking another option can turn sorting off. */
    private static volatile boolean sChosenHere;

    /** Adds our two entries to every {@code sort_options} list in the response. */
    static String rewriteResponse(String json) {
        if (json == null || json.indexOf("\"sort_options\"") < 0) return json;
        try {
            JSONObject root = new JSONObject(json);
            if (!addOptions(root, 0)) return json;
            return root.toString();
        } catch (Throwable t) {
            SortByRatingsHelper.log("meesho sort menu failed: " + t);
            return json;
        }
    }

    private static boolean addOptions(Object node, int depth) {
        if (depth > 6 || node == null) return false;
        boolean changed = false;
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            JSONArray options = obj.optJSONArray("sort_options");
            if (options != null && options.length() > 0 && !hasOurs(options)) {
                JSONObject base = options.optJSONObject(0);
                if (base != null && base.has("sort_by")) {
                    SortState.Mode mode = SortState.mode();
                    if (mode != SortState.Mode.OFF) {
                        // Our entry is the chosen one; the server's own options are not.
                        for (int i = 0; i < options.length(); i++) {
                            JSONObject option = options.optJSONObject(i);
                            if (option != null) put(option, "selected", false);
                        }
                    }
                    options.put(copyOf(base, MOST_RATED, mode == SortState.Mode.COUNT));
                    options.put(copyOf(base, TOP_RATED, mode == SortState.Mode.RATING));
                    changed = true;
                }
            }
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

    private static boolean hasOurs(JSONArray options) {
        for (int i = 0; i < options.length(); i++) {
            JSONObject option = options.optJSONObject(i);
            if (option != null && MOST_RATED.equals(option.optString("display_name"))) return true;
        }
        return false;
    }

    private static JSONObject copyOf(JSONObject base, String label, boolean selected) {
        JSONObject copy = new JSONObject();
        put(copy, "selected", selected);
        put(copy, "display_name", label);
        put(copy, "sort_by", base.opt("sort_by"));
        put(copy, "sort_order", base.opt("sort_order"));
        return copy;
    }

    private static void put(JSONObject obj, String key, Object value) {
        try {
            obj.put(key, value);
        } catch (Exception ignored) {
            // keys are constants, values are plain
        }
    }

    /**
     * Called with the {@code SortOption} the app is about to serialise into a request.
     * Reads it through {@code toString()} (field names are obfuscated, the text is not).
     */
    static void onSortWritten(Object option) {
        if (option == null) return;
        Matcher m = WRITTEN.matcher(String.valueOf(option));
        if (!m.find() || !"true".equals(m.group(1))) return;
        String name = m.group(2);
        if (MOST_RATED.equals(name)) {
            sChosenHere = true;
            SortState.setMode(SortState.Mode.COUNT);
        } else if (TOP_RATED.equals(name)) {
            sChosenHere = true;
            SortState.setMode(SortState.Mode.RATING);
        } else if (sChosenHere) {
            // The user went back to one of the app's own options.
            sChosenHere = false;
            SortState.setMode(SortState.Mode.OFF);
        }
    }
}
