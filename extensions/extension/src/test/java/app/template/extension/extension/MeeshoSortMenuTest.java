package app.template.extension.extension;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

public class MeeshoSortMenuTest {

    @Before
    public void off() {
        SortState.forTest(SortState.Mode.OFF, false, false);
    }

    private static String response() throws Exception {
        JSONArray options = new JSONArray()
            .put(new JSONObject().put("selected", true).put("display_name", "Relevance")
                .put("sort_by", "popularity").put("sort_order", "desc"))
            .put(new JSONObject().put("selected", false).put("display_name", "New Arrivals")
                .put("sort_by", "created").put("sort_order", "desc"));
        return new JSONObject().put("count", 12).put("sort_options", options)
            .put("padding", "x".repeat(40)).toString();
    }

    private static JSONArray options(String json) throws Exception {
        return new JSONObject(json).getJSONArray("sort_options");
    }

    @Test
    public void addsBothEntriesCopyingTheServersOwnFirstOption() throws Exception {
        JSONArray o = options(MeeshoSortMenu.rewriteResponse(response()));
        assertEquals(4, o.length());
        assertEquals("Most rated", o.getJSONObject(2).getString("display_name"));
        assertEquals("popularity", o.getJSONObject(2).getString("sort_by"));
        assertEquals("desc", o.getJSONObject(3).getString("sort_order"));
        assertEquals("Top rated", o.getJSONObject(3).getString("display_name"));
        // nothing chosen: the server's own selection is untouched
        assertEquals(true, o.getJSONObject(0).getBoolean("selected"));
        assertEquals(false, o.getJSONObject(2).getBoolean("selected"));
    }

    @Test
    public void marksTheActiveModeAsSelected() throws Exception {
        SortState.forTest(SortState.Mode.COUNT, false, false);
        JSONArray o = options(MeeshoSortMenu.rewriteResponse(response()));
        assertEquals(false, o.getJSONObject(0).getBoolean("selected"));
        assertEquals(true, o.getJSONObject(2).getBoolean("selected"));
        assertEquals(false, o.getJSONObject(3).getBoolean("selected"));
    }

    @Test
    public void doesNotAddTwiceAndIgnoresOtherResponses() throws Exception {
        String once = MeeshoSortMenu.rewriteResponse(response());
        assertEquals(4, options(MeeshoSortMenu.rewriteResponse(once)).length());
        String other = "{\"catalogs\":[],\"padding\":\"" + "y".repeat(60) + "\"}";
        assertSame(other, MeeshoSortMenu.rewriteResponse(other));
    }

    @Test
    public void choosingOurEntryTurnsSortingOnAndLeavingItTurnsItOff() {
        MeeshoSortMenu.onSortWritten("SortOption(selected=true, name=Top rated, sortBy=popularity, sortOrder=desc)");
        assertEquals(SortState.Mode.RATING, SortState.mode());
        MeeshoSortMenu.onSortWritten("SortOption(selected=true, name=Most rated, sortBy=popularity, sortOrder=desc)");
        assertEquals(SortState.Mode.COUNT, SortState.mode());
        // an unselected option being serialised changes nothing
        MeeshoSortMenu.onSortWritten("SortOption(selected=false, name=Relevance, sortBy=popularity, sortOrder=desc)");
        assertEquals(SortState.Mode.COUNT, SortState.mode());
        MeeshoSortMenu.onSortWritten("SortOption(selected=true, name=Relevance, sortBy=popularity, sortOrder=desc)");
        assertEquals(SortState.Mode.OFF, SortState.mode());
    }

    @Test
    public void appOptionsNeverTouchTheModeUnlessWeSetIt() {
        SortState.forTest(SortState.Mode.RATING, false, false); // set via the floating button
        MeeshoSortMenu.onSortWritten("SortOption(selected=true, name=Relevance, sortBy=a, sortOrder=b)");
        assertEquals(SortState.Mode.RATING, SortState.mode());
    }
}
