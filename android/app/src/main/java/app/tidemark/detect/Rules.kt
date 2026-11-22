package app.tidemark.detect

import android.content.Context
import org.json.JSONObject

data class AppRule(
    val packageName: String,
    val name: String,
    val surface: String,
    val pagerIds: Set<String>,
    val genericFallback: Boolean,
    /** Target-app versionCode ranges that passed the device test protocol. */
    val verifiedVersions: List<LongRange>,
) {
    /** Unknown version (app not installed) is never "verified". */
    fun isVerified(versionCode: Long?): Boolean =
        versionCode != null && verifiedVersions.any { versionCode in it }
}

data class Rules(val version: Int, val settleMs: Long, val apps: Map<String, AppRule>) {
    fun forPackage(pkg: String): AppRule? = apps[pkg]

    companion object {
        const val ASSET = "detectors/v1.json"

        /** Rules ship inside the APK. Nothing is fetched; a fix needs an app update. */
        fun load(context: Context): Rules =
            parse(context.assets.open(ASSET).bufferedReader().use { it.readText() })

        fun parse(json: String): Rules {
            val root = JSONObject(json)
            val arr = root.getJSONArray("apps")
            val apps = (0 until arr.length()).associate { i ->
                val o = arr.getJSONObject(i)
                val ids = o.optJSONArray("pagerIds")
                val idSet = if (ids == null) emptySet() else (0 until ids.length()).map { ids.getString(it) }.toSet()
                val vv = o.optJSONArray("verifiedVersions")
                val ranges = if (vv == null) emptyList() else (0 until vv.length()).map {
                    val r = vv.getJSONObject(it)
                    r.getLong("min")..r.optLong("max", Long.MAX_VALUE)
                }
                val rule = AppRule(
                    packageName = o.getString("package"),
                    name = o.getString("name"),
                    surface = o.getString("surface"),
                    pagerIds = idSet,
                    genericFallback = o.optBoolean("genericFallback", true),
                    verifiedVersions = ranges,
                )
                rule.packageName to rule
            }
            return Rules(root.getInt("version"), root.optLong("settleMs", 400L), apps)
        }
    }
}
