# Sort by number of ratings — Amazon + Flipkart (Morphe patch scaffold)

Client-side DOM reorder, descending by parsed ratings count. Neither site
offers a server-side "sort by ratings count", so this sorts only the cards
already loaded on the page.

## What is here

| File | Copy to (in your `morphe-patches` fork) |
|---|---|
| `extension/SortByRatingsHelper.java` | `extensions/extension/src/main/java/app/template/extension/extension/SortByRatingsHelper.java` |
| `amazon/sortbyratingscount/SortByRatingsCountPatch.kt` | `patches/src/main/kotlin/app/template/patches/amazon/sortbyratingscount/SortByRatingsCountPatch.kt` |
| `amazon/sortbyratingscount/Fingerprints.kt` | `patches/src/main/kotlin/app/template/patches/amazon/sortbyratingscount/Fingerprints.kt` |
| `flipkart/sortbyratingscount/SortByRatingsCountPatch.kt` | `patches/src/main/kotlin/app/template/patches/flipkart/sortbyratingscount/SortByRatingsCountPatch.kt` |
| `flipkart/sortbyratingscount/Fingerprints.kt` | `patches/src/main/kotlin/app/template/patches/flipkart/sortbyratingscount/Fingerprints.kt` (placeholders — see below) |
| `shared/Constants-snippet.kt` | merge `FLIPKART_COMPATIBILITY` into `patches/.../shared/Constants.kt` |

No new permissions, no network calls. The injected JS is a fixed string;
site/URL arrive as JSON-encoded args (same pattern as `AmazonHelper.injectPriceCharts`).

## Amazon — why this works as-is

Amazon Shopping (`com.amazon.mShop.android.shopping` /
`in.amazon.mShop.android.shopping`) renders search/listings in
`MShopWebView` (`Lcom/amazon/mShop/web/MShopWebViewClient::onPageFinished`,
`InteractionWebFragment::setWebView`). The fingerprints here are copied from
the verified `removeads` / `pricecharts` patches, so they match v32.13.x.
The helper runs only when the URL looks like a listing
(`/s?`, `/s/`, `/search/`, `k=`, `rh=`), adds a floating
`Sort: Most rated` button, and re-orders `[data-component-type="s-search-result"]`
cards by parsed count. Sponsored cards (`AdHolder`, `puis-sponsored-label-text`,
`[data-ad-feedback]`, …) are skipped.

Count parsing: strips commas (handles Indian `1,23,456` too), `K`/`M`
suffixes, requires `rating|review` text or parens or a star-bearing card, and
explicitly rejects `out of 5 stars` so `4.3` never counts as 4 ratings.

## Flipkart — one manual step required

`com.flipkart.android` has no existing patches in the template and its search
is believed to be native (RecyclerView), not WebView:

1. `apktool d flipkart.apk`, then in jadx search for
   `extends android.webkit.WebViewClient` and `onPageFinished`.
   - If a search/listing WebView exists, point
     `FlipkartWebViewClientOnPageFinishedFingerprint` at its real
     `definingClass` (+ a unique `strings` entry) and the provided patch works.
2. If search is native (likely), WebView injection can never reach it. Instead:
   - Search smali/dex for `"sort"`, `"popularity"`, `"numberOfRatings"`,
     `"ratingCount"` near the listing adapter / API builder.
   - Hook the comparator or the sort-param builder to order by the
     ratings-count field descending. That fingerprint has to come from your
     APK version — it cannot be guessed here.
3. Set `FLIPKART_COMPATIBILITY`'s `version`/`versionCode` to the APK you analyzed.

## Build / test

1. Copy files per the table, merge the Constants snippet.
2. `./gradlew :patches:build` (or your usual patch-bundle build), add the
   bundle as a Morphe source.
3. Patch Amazon Shopping with `Sort by number of ratings` enabled (default off).
4. Open a search (e.g. `headphones`), scroll to load results, tap
   `Sort: Most rated`. Tap again to restore original order.
5. Test cases: `12,345 ratings`, `(12,345)`, `1.2K ratings`, Indian
   `1,23,456`, cards with no ratings (sort last), sponsored cards (unmoved),
   infinite-scroll append (re-sorts while active via MutationObserver).

## Limits (tell users up front)

- Sorts loaded cards only — scroll-then-sort for more coverage.
- Markup-sensitive: an Amazon/Flipkart redesign breaks selectors until updated.
- `s=review-rank` (Amazon) sorts by *average* rating, not count — not a substitute.
