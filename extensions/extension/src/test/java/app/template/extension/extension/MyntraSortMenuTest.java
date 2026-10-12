package app.template.extension.extension;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

public class MyntraSortMenuTest {

    @Before
    public void off() {
        SortState.forTest(SortState.Mode.OFF, false, false);
    }

    private static JSONObject option(String id, String name) throws Exception {
        return new JSONObject().put("id", id).put("name", name).put("isApplied", false)
            .put("onHalfcardClick", new JSONObject().put("type", "CHANGE_URI").put("route", "/tshirt")
                .put("tracking", new JSONObject().put("dataOverride", new JSONObject()
                    .put("widget", new JSONObject().put("type", "Sort").put("name", name)))));
    }

    private static JSONObject page() throws Exception {
        JSONArray options = new JSONArray().put(option("new", "What's new"))
            .put(option("popularity", "Popularity")).put(option("Customer Rating", "Customer Rating"));
        JSONObject data = new JSONObject().put("halfcardData", new JSONObject().put("filterOptions", options));
        return new JSONObject().put("pageLevelComponents", new JSONObject().put("footers", new JSONArray()
            .put(new JSONObject().put("itemData", new JSONObject().put("data", new JSONObject()
                .put("buttons", new JSONArray().put(data)))))));
    }

    private static JSONArray options(JSONObject page) {
        return page.optJSONObject("pageLevelComponents").optJSONArray("footers").optJSONObject(0)
            .optJSONObject("itemData").optJSONObject("data").optJSONArray("buttons").optJSONObject(0)
            .optJSONObject("halfcardData").optJSONArray("filterOptions");
    }

    @Test
    public void addsBothEntriesCopyingPopularity() throws Exception {
        JSONObject page = page();
        assertTrue(MyntraSortMenu.addOptions(page, 0));
        JSONArray o = options(page);
        assertEquals(5, o.length());
        assertEquals("morphe_most_rated", o.getJSONObject(3).getString("id"));
        assertEquals("Most rated", o.getJSONObject(3).getString("name"));
        assertEquals("Top rated", o.getJSONObject(4).getString("name"));
        // tracking name follows, the rest of the action is the popularity option's own
        assertEquals("Most rated", o.getJSONObject(3).getJSONObject("onHalfcardClick")
            .getJSONObject("tracking").getJSONObject("dataOverride").getJSONObject("widget").getString("name"));
        assertEquals("CHANGE_URI", o.getJSONObject(3).getJSONObject("onHalfcardClick").getString("type"));
        // not added twice
        assertFalse(MyntraSortMenu.addOptions(page, 0));
        assertEquals(5, options(page).length());
    }

    @Test
    public void marksActiveModeAndLeavesPagesWithoutPopularityAlone() throws Exception {
        SortState.forTest(SortState.Mode.RATING, false, false);
        JSONObject page = page();
        MyntraSortMenu.addOptions(page, 0);
        JSONArray o = options(page);
        assertEquals(false, o.getJSONObject(1).getBoolean("isApplied"));
        assertEquals(true, o.getJSONObject(4).getBoolean("isApplied"));

        JSONObject other = new JSONObject().put("filterOptions", new JSONArray().put(option("new", "What's new")));
        assertFalse(MyntraSortMenu.addOptions(other, 0));
    }

    @Test
    public void requestWithOurSortIdTurnsSortingOnAndAnotherIdTurnsItOff() {
        MyntraSortMenu.onPageRequest("{\"uri\":\"/v3/layout/search/tshirt?sort=morphe_most_rated\"}");
        assertEquals(SortState.Mode.COUNT, SortState.mode());
        // paging and other pages carry no sort parameter: unchanged
        MyntraSortMenu.onPageRequest("{\"uri\":\"/v3/layout/search/tshirt?p=2\"}");
        assertEquals(SortState.Mode.COUNT, SortState.mode());
        MyntraSortMenu.onPageRequest("{\"uri\":\"/v3/layout/search/tshirt?sort=morphe_top_rated&p=2\"}");
        assertEquals(SortState.Mode.RATING, SortState.mode());
        MyntraSortMenu.onPageRequest("{\"uri\":\"/v3/layout/search/tshirt?sort=price_asc\"}");
        assertEquals(SortState.Mode.OFF, SortState.mode());
    }

    @Test
    public void appSortsNeverTouchTheModeUnlessWeSetIt() {
        SortState.forTest(SortState.Mode.COUNT, false, false); // set via the floating button
        MyntraSortMenu.onPageRequest("{\"uri\":\"/v3/layout/search/tshirt?sort=price_asc\"}");
        assertEquals(SortState.Mode.COUNT, SortState.mode());
    }
}
