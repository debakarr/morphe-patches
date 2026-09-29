package app.template.patches.meesho.sortbyratingscount

import app.morphe.patcher.Fingerprint

/**
 * Retrofit's Moshi converter. Every Meesho API model (catalog feeds, search,
 * collections, ...) is parsed here, and okhttp's ResponseBody API is not
 * obfuscated, so the body can be swapped for a rewritten one up front.
 */
internal val MoshiResponseBodyConverterFingerprint = Fingerprint(
    definingClass = "Lretrofit2/converter/moshi/MoshiResponseBodyConverter;",
    name = "convert",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Lokhttp3/ResponseBody;"),
)
