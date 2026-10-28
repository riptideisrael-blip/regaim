package com.regaim.app

/**
 * Shared children (stage 1 of the family file): what to do so that both parents' phones show the same children.
 * The server keeps the family's children; each phone keeps its own list and a link from its child to the server's.
 * This object only decides; [ChildSync] does it. No Android here (unit-tested).
 */
object ChildSyncPlan {
    /** A child on this phone (its id is local). [birth] is yyyy-MM-dd or empty. */
    data class Local(val id: String, val name: String, val birth: String)
    /** A child on the server. [deleted] = a parent removed them (kept as a mark so the other phone removes them too). */
    data class Server(val id: String, val name: String, val birth: String, val deleted: Boolean = false)

    sealed class Action {
        /** Upload a child the server doesn't have yet. */
        data class Upload(val local: Local) : Action()
        /** The same child on both sides (same name, and the same birthday or one is missing): just link them. */
        data class Link(val localId: String, val serverId: String) : Action()
        /** A child only the server has (added on the other parent's phone). */
        data class AddLocal(val server: Server) : Action()
        /** The server's details are newer: take them. */
        data class UpdateLocal(val localId: String, val server: Server) : Action()
        /** This phone changed the child: send the change. */
        data class UpdateServer(val serverId: String, val local: Local) : Action()
        /** This parent removed the child here: remove for the family (a mark, not a hard delete). */
        data class DeleteServer(val serverId: String) : Action()
        /** The other parent removed the child. */
        data class RemoveLocal(val localId: String) : Action()
        /** The link points at a child the server no longer shows: forget the link (the child stays on the phone). */
        data class Unlink(val localId: String) : Action()
    }

    /** What was last agreed for a child: compared with both sides to see who changed. */
    fun key(name: String, birth: String) = name.trim() + "|" + birth.trim()

    private fun norm(s: String) = s.trim().replace(Regex("\\s+"), " ").lowercase()

    /** A real yyyy-MM-dd date (what the server's date column accepts), or empty. */
    fun cleanBirth(b: String): String {
        val t = b.trim()
        val m = Regex("""^(\d{4})-(\d{2})-(\d{2})$""").find(t) ?: return ""
        val (y, mo, d) = m.destructured
        return if (y.toInt() in 1900..2100 && mo.toInt() in 1..12 && d.toInt() in 1..31) t else ""
    }

    /**
     * [links] local id → server id; [synced] local id → [key] at the last sync. A parent (circle owner or one of the
     * two partners) changes the server; anyone else only takes what the server has.
     */
    fun plan(local: List<Local>, server: List<Server>, links: Map<String, String>, synced: Map<String, String>, parent: Boolean): List<Action> {
        val out = mutableListOf<Action>()
        val localById = local.associateBy { it.id }
        val serverById = server.associateBy { it.id }
        val localOf = links.entries.associate { (l, s) -> s to l }
        val linkedLocal = mutableSetOf<String>()
        links.forEach { (l, s) -> if (serverById.containsKey(s)) linkedLocal += l }

        // Links to children the server no longer lists (no access, or removed for good).
        links.forEach { (l, s) -> if (!serverById.containsKey(s)) out += Action.Unlink(l) }

        for (s in server) {
            val lid = localOf[s.id]
            val l = lid?.let { localById[it] }
            if (s.deleted) {
                if (lid != null && l != null) out += Action.RemoveLocal(lid)
                continue
            }
            when {
                lid == null -> {
                    val match = local.firstOrNull { c ->
                        c.id !in linkedLocal && norm(c.name) == norm(s.name) &&
                            (cleanBirth(c.birth).isEmpty() || s.birth.isEmpty() || cleanBirth(c.birth) == s.birth)
                    }
                    if (match != null) {
                        linkedLocal += match.id
                        out += Action.Link(match.id, s.id)
                        if (cleanBirth(match.birth).isEmpty() && s.birth.isNotEmpty()) out += Action.UpdateLocal(match.id, s)
                        else if (parent && s.birth.isEmpty() && cleanBirth(match.birth).isNotEmpty()) out += Action.UpdateServer(s.id, match)
                    } else out += Action.AddLocal(s)
                }
                l == null -> out += if (parent) Action.DeleteServer(s.id) else Action.AddLocal(s)
                else -> {
                    val last = synced[lid]
                    val mine = key(l.name, cleanBirth(l.birth))
                    val theirs = key(s.name, s.birth)
                    if (mine == theirs) continue
                    val localChanged = last != null && last != mine
                    if (parent && (localChanged || last == null)) out += Action.UpdateServer(s.id, l)
                    else out += Action.UpdateLocal(lid, s)
                }
            }
        }
        if (parent) for (l in local) if (l.id !in linkedLocal && out.none { it is Action.Link && it.localId == l.id }) out += Action.Upload(l)
        return out
    }
}
