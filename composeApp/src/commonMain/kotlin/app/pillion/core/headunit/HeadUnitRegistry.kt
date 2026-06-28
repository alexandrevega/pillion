package app.pillion.core.headunit

/**
 * The single place that knows which bikes exist (OCP). First-party paths (NaviLite, SDL) and any
 * future plugins register here; the onboarding selector and [SessionFactory] read from it. Adding a
 * bike requires no change anywhere else.
 */
object HeadUnitRegistry {
    private val profiles = LinkedHashMap<String, HeadUnitProfile>()

    fun register(profile: HeadUnitProfile) {
        profiles[profile.id] = profile
    }

    fun all(): List<HeadUnitProfile> = profiles.values.toList()

    fun byId(id: String): HeadUnitProfile? = profiles[id]

    fun clear() {
        profiles.clear()
    }
}
