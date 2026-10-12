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

/**
 * `CatalogsRequestBody(filter, searchSessionId, cursor, offset, limit, ...)`:
 * the request model of every catalog / search listing call. `limit` is the
 * page size, register p5.
 */
internal val CatalogsRequestBodyConstructorFingerprint = Fingerprint(
    definingClass = "Lcom/meesho/discovery/catalog/api/model/CatalogsRequestBody;",
    name = "<init>",
    custom = { method, _ ->
        method.parameterTypes.size == 10 &&
            method.parameterTypes[3] == "I" && method.parameterTypes[4] == "I"
    },
)

/**
 * Moshi adapter that writes a `SortOption` ({selected, display_name, sort_by, sort_order})
 * into listing and sort-filter requests. `p2` is the option being written.
 */
internal val SortOptionToJsonFingerprint = Fingerprint(
    definingClass = "Lcom/meesho/sortfilter/api/model/SortOptionJsonAdapter;",
    name = "toJson",
    custom = { method, _ ->
        method.parameterTypes.size == 2 && method.parameterTypes[1] == "Ljava/lang/Object;"
    },
)
