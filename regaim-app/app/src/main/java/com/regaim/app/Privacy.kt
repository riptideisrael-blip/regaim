package com.regaim.app

/**
 * The single source of truth for who may see a memory.
 * Order matters: a higher level is visible to everyone a lower level is visible to.
 * Private < Family < Friends < Public.
 * Every future social feature (feed, profiles, follow, search, share) must ask
 * [Privacy.canSee] and never invent its own rule. New memories start Private.
 */
enum class Privacy(val key: String, private val labelHe: String, private val labelEn: String, val icon: String, val available: Boolean) {
    PRIVATE("private", "רק אני", "Only me", "🔒", true),
    FAMILY("family", "משפחה", "Family", "👨‍👩‍👧", true),
    FRIENDS("friends", "חברים", "Friends", "🤝", false),   // reserved: needs follow/friend graph
    PUBLIC("public", "ציבורי", "Public", "🌍", true);

    val label: String get() = L.t(labelHe, labelEn)

    companion object {
        val DEFAULT = PRIVATE
        fun of(key: String?) = values().firstOrNull { it.key == key } ?: DEFAULT

        /** Relationship of the viewer to the memory's owner. */
        enum class Viewer { OWNER, FAMILY_MEMBER, FRIEND, FOLLOWER_OR_ANYONE }

        fun canSee(level: Privacy, viewer: Viewer): Boolean = when (viewer) {
            Viewer.OWNER -> true
            Viewer.FAMILY_MEMBER -> level >= FAMILY
            Viewer.FRIEND -> level >= FRIENDS
            Viewer.FOLLOWER_OR_ANYONE -> level == PUBLIC
        }
    }
}
