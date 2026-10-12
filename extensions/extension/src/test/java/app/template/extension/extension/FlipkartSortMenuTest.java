package app.template.extension.extension;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

public class FlipkartSortMenuTest {

    @Before
    public void off() {
        SortState.forTest(SortState.Mode.OFF, false, false);
    }

    private static JSONObject card(String label, String sortValue, boolean selected) throws Exception {
        JSONObject op = new JSONObject().put("oldValue", "relevance").put("value", sortValue)
            .put("operation", "UPDATE").put("key", "sort");
        JSONObject action = new JSONObject().put("type", "DEFERRED_URL_UPDATE")
            .put("params", new JSONObject().put("operationList", new JSONArray().put(op)))
            .put("tracking", new JSONObject().put("contentTitle", label));
        JSONObject selection = new JSONObject().put("action", action)
            .put("value", new JSONObject().put("actionType", "FILTER").put("defaultSelected", selected)
                .put("itemKey", "Key-" + label));
        return new JSONObject().put("value", new JSONObject()
            .put("trackerData_0", new JSONObject().put("tracking", new JSONObject().put("contentTitle", label)))
            .put("RadioButtonData_0", new JSONObject().put("value", new JSONObject().put("id", "Key-" + label).put("label", label)))
            .put("SelectionViewData_0", selection));
    }

    private static String response() throws Exception {
        JSONArray cards = new JSONArray()
            .put(card("Relevance", "relevance", true))
            .put(card("Popularity", "popularity", false))
            .put(card("Newest first", "recency_desc", false));
        return new JSONObject().put("RESPONSE", new JSONObject().put("slots", new JSONArray()
            .put(new JSONObject().put("widget", new JSONObject().put("data", new JSONObject()
                .put("dlsData", new JSONObject().put("SelectionGroupData_0", new JSONObject().put("value", cards)))))))).toString();
    }

    private static JSONArray cards(String json) throws Exception {
        return new JSONObject(json).getJSONObject("RESPONSE").getJSONArray("slots").getJSONObject(0)
            .getJSONObject("widget").getJSONObject("data").getJSONObject("dlsData")
            .getJSONObject("SelectionGroupData_0").getJSONArray("value");
    }

    private static String sortValue(JSONObject card) {
        return card.optJSONObject("value").optJSONObject("SelectionViewData_0").optJSONObject("action")
            .optJSONObject("params").optJSONArray("operationList").optJSONObject(0).optString("value");
    }

    @Test
    public void addsBothCardsCopyingPopularity() throws Exception {
        JSONArray c = cards(FlipkartSortMenu.rewriteResponse(response()));
        assertEquals(5, c.length());
        JSONObject most = c.getJSONObject(3);
        assertEquals("morphe_most_rated", sortValue(most));
        assertEquals("Most rated", most.getJSONObject("value").getJSONObject("RadioButtonData_0")
            .getJSONObject("value").getString("label"));
        assertEquals("Key-Most rated", most.getJSONObject("value").getJSONObject("SelectionViewData_0")
            .getJSONObject("value").getString("itemKey"));
        assertEquals("DEFERRED_URL_UPDATE", most.getJSONObject("value").getJSONObject("SelectionViewData_0")
            .getJSONObject("action").getString("type"));
        assertEquals("morphe_top_rated", sortValue(c.getJSONObject(4)));
        // the server's own selection is untouched while no mode is on
        assertEquals(true, c.getJSONObject(0).getJSONObject("value").getJSONObject("SelectionViewData_0")
            .getJSONObject("value").getBoolean("defaultSelected"));
    }

    @Test
    public void marksTheActiveModeAndIsIdempotent() throws Exception {
        SortState.forTest(SortState.Mode.COUNT, false, false);
        String once = FlipkartSortMenu.rewriteResponse(response());
        JSONArray c = cards(once);
        assertEquals(false, c.getJSONObject(0).getJSONObject("value").getJSONObject("SelectionViewData_0")
            .getJSONObject("value").getBoolean("defaultSelected"));
        assertEquals(true, c.getJSONObject(3).getJSONObject("value").getJSONObject("SelectionViewData_0")
            .getJSONObject("value").getBoolean("defaultSelected"));
        assertEquals(5, cards(FlipkartSortMenu.rewriteResponse(once)).length());
    }

    @Test
    public void otherResponsesAreUntouched() {
        String other = "{\"RESPONSE\":{\"slots\":[]},\"padding\":\"" + "x".repeat(60) + "\"}";
        assertSame(other, FlipkartSortMenu.rewriteResponse(other));
    }

    @Test
    public void pageUriWithOurSortTurnsSortingOnAndAnotherSortTurnsItOff() {
        FlipkartSortMenu.onPageUri("/search?q=earbuds&sort=morphe_most_rated");
        assertEquals(SortState.Mode.COUNT, SortState.mode());
        FlipkartSortMenu.onPageUri("/search?q=earbuds&page=2");
        assertEquals(SortState.Mode.COUNT, SortState.mode());
        FlipkartSortMenu.onPageUri("/search?sort=morphe_top_rated&q=x");
        assertEquals(SortState.Mode.RATING, SortState.mode());
        FlipkartSortMenu.onPageUri("/search?q=x&sort=price_asc");
        assertEquals(SortState.Mode.OFF, SortState.mode());
    }

    @Test
    public void appSortsNeverTouchTheModeUnlessWeSetIt() {
        SortState.forTest(SortState.Mode.RATING, false, false);
        FlipkartSortMenu.onPageUri("/search?q=x&sort=popularity");
        assertEquals(SortState.Mode.RATING, SortState.mode());
    }
}
