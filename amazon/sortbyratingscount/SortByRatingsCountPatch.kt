package app.template.patches.amazon.sortbyratingscount

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.shared.Constants.AMAZON_IN_COMPATIBILITY
import app.template.patches.shared.Constants.AMAZON_SHOPPING_COMPATIBILITY

private const val HELPER = "Lapp/template/extension/extension/SortByRatingsHelper;"

@Suppress("unused")
val amazonSortByRatingsCountPatch = bytecodePatch(
    name = "Sort by number of ratings",
    description = "Adds a 'Sort: Most rated' button on Amazon search/listing pages that re-orders loaded results by ratings count, descending.",
    default = false,
) {
    compatibleWith(AMAZON_SHOPPING_COMPATIBILITY, AMAZON_IN_COMPATIBILITY)
    extendWith("extensions/extension.mpe")

    execute {
        // Non-jumpstarted: p1=WebView, p2=url (method has free v0).
        // Same register layout as PriceChartsPatch's onPageFinished hook.
        MShopWebViewClientOnPageFinishedFingerprint.method.addInstructions(
            0,
            "invoke-static {p1, p2}, $HELPER->injectSortByRatings(Landroid/webkit/WebView;Ljava/lang/String;)V",
        )

        // Jumpstarted + all: p1=MShopWebView (is-a WebView), url unknown here
        // so pass null — helper re-reads webView.url at runtime.
        // If your Morphe version rejects null urls, drop this second hook;
        // non-jumpstarted pages will still work.
        InteractionWebFragmentSetWebViewFingerprint.method.addInstructions(
            0,
            """
                invoke-virtual {p1}, Landroid/webkit/WebView;->getUrl()Ljava/lang/String;
                move-result-object v0
                invoke-static {p1, v0}, $HELPER->injectSortByRatings(Landroid/webkit/WebView;Ljava/lang/String;)V
            """.trimIndent(),
        )
    }
}
