[![Add to Morphe](add-to-morphe.svg)](https://morphe.software/add-source?github=debakarr/morphe-patches)

# Debakar's Morphe Patches

Morphe patch source for **sorting search results by number of ratings** (descending) and **removing ad/sponsored items** on Amazon and Flipkart.

> **Neither app offers a "sort by ratings count" option.** Amazon's "Avg. Customer Review" sorts by *average* rating; Flipkart's "Popularity" is an opaque heuristic. These patches add true descending sort by the *number* of ratings/reviews, and strip sponsored placements.

## Patches

### Amazon — Sort by number of ratings

**App:** Amazon Shopping / Amazon India (WebView)

| Detail | Value |
|---|---|
| Approach | Client-side DOM reorder via `evaluateJavascript` |
| Scope | Search results pages |
| Compatibility | `com.amazon.mShop.android.shopping` v32.13.2.100 · `in.amazon.mShop.android.shopping` v32.18.0.300 / v32.16.2.300 |

On search/listing pages two floating buttons appear:

- **"Sort: Most rated"** — reorders product cards descending by parsed review count; tap again to restore.
- **"Remove ads"** — hides sponsored/ad cards; tap again to show them. New cards loaded by infinite scroll are handled automatically.

### Flipkart — Sort by number of ratings

**App:** Flipkart (React Native + ATLAS feed)

| Detail | Value |
|---|---|
| Approach | Hook on the base mapi Gson converter (`converter.h.convert`), plus the RN `NetworkCaller` callbacks |
| Scope | Every mapi page: search, category, browse, pagination, … |
| Compatibility | `com.flipkart.android` v9.15 (versionCode 3240500) · v9.13 (versionCode 3220300) |

Flipkart serves product listings as ATLAS feed rows (`RESPONSE.slots[].widget.data.dlsData`). Each row holds a list of product cards; every card carries the rating count in `ratingData_0.reviewText` (e.g. `| 4.7K+`) and ad cards carry a `tagData_0` badge (`AD` / `Sponsored`). Whole ad widgets (e.g. the "Top rated products" carousel with a header `AD` badge) are removed as well.

The patch wraps the response reader inside the base mapi Gson converter, parses the JSON, re-orders every product row by rating count descending, drops ad cards and ad widgets, and hands Gson the rewritten JSON — before any native or JS consumer sees it.

## Add to Morphe

1. Open **Morphe Manager**
2. Go to **Settings → Patch sources**
3. Add:
   ```
   https://github.com/debakarr/morphe-patches
   ```
4. Select the app, enable the patch, and build

Or tap the badge at the top of this page.

## How it works

The Morphe patcher builds a `.mpp` bundle containing patch metadata and bytecode. When applied, patches modify the target APK's DEX files to inject helper logic. For these patches:

- **Amazon:** `SortByRatingsHelper.injectSortByRatings()` is called from a WebView `onPageFinished` hook. It evaluates JavaScript that collects product cards, extracts rating counts from text nodes, reorders them, and can hide sponsored cards.
- **Flipkart:** `SortByRatingsHelper.processResponseReader()` is injected at the start of the base mapi Gson converter (`converter.h.convert`). It reads the response body, rewrites every ATLAS product row (sort by rating count descending, drop ad cards and ad widgets), and returns a reader over the modified JSON. Additional hooks on the RN `NetworkCaller` callbacks cover the legacy raw-string path.

## Development

```bash
# Build the patch bundle (requires Gradle + Java 21)
./gradlew patches:build

# Release (via GitHub Actions)
gh workflow run release.yml --ref main
```

## License

This project is licensed under the GNU General Public License v3.0 — see [LICENSE](LICENSE) for details.
