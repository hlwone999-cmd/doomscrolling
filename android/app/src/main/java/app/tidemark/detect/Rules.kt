package app.tidemark.detect

import android.content.Context
import org.json.JSONObject

data class AppRule(
    val packageName: String,
    val name: String,
    val surface: String,
    /** View-id fragments (lowercase) that raise confidence; never sufficient on their own. */
    val pagerIdHints: List<String>,
    val genericFallback: Boolean,
    /** Target-app versionCode ranges that passed the device test protocol. */
    val verifiedVersions: List<LongRange>,
) {
    /** Unknown version (app not installed) is never "verified". */
    fun isVerified(versionCode: Long?): Boolean =
        versionCode != null && verifiedVersions.any { versionCode in it }

    /** True when the entry name of a view id ("pkg:id/clips_viewer_pager") contains a hint. */
    fun hintMatches(viewId: String?): Boolean {
        if (viewId == null || pagerIdHints.isEmpty()) return false
        val entry = viewId.substringAfter(":id/").lowercase()
        return pagerIdHints.any { entry.contains(it) }
    }
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
                val hints = o.optJSONArray("pagerIdHints")
                val hintList = if (hints == null) emptyList() else (0 until hints.length()).map { hints.getString(it).lowercase() }
                val vv = o.optJSONArray("verifiedVersions")
                val ranges = if (vv == null) emptyList() else (0 until vv.length()).map {
                    val r = vv.getJSONObject(it)
                    r.getLong("min")..r.optLong("max", Long.MAX_VALUE)
                }
                val rule = AppRule(
                    packageName = o.getString("package"),
                    name = o.getString("name"),
                    surface = o.getString("surface"),
                    pagerIdHints = hintList,
                    genericFallback = o.optBoolean("genericFallback", true),
                    verifiedVersions = ranges,
                )
                rule.packageName to rule
            }
            return Rules(root.getInt("version"), root.optLong("settleMs", 250L), apps)
        }
    }
}
