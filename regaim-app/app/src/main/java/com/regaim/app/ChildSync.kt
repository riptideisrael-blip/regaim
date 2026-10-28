package com.regaim.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Keeps the children the same on both parents' phones (stage 1 of the family file). [ChildSyncPlan] decides; this
 * does it, in the background, at most once a minute (or right after the parent edits the family). Until the server
 * has the shared children (shared-children.sql), it does nothing and the phone works as before.
 */
object ChildSync {
    private const val TAG = "ChildSync"
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var lastRun = 0L
    @Volatile private var offUntil = 0L

    fun kick(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        if (!Circle.joined(app) || !Account.signedIn(app)) return
        val now = System.currentTimeMillis()
        if (now < offUntil || (!force && now - lastRun < 60_000L)) return
        lastRun = now
        exec.execute {
            try { run(app) } catch (e: Exception) { Log.w(TAG, "sync", e) }
        }
    }

    private fun links(ctx: Context, name: String): MutableMap<String, String> = try {
        val o = JSONObject(Settings.prefs(ctx).getString(name, "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }.toMutableMap()
    } catch (_: Exception) { mutableMapOf() }

    private fun saveMap(ctx: Context, name: String, m: Map<String, String>) =
        Settings.prefs(ctx).edit().putString(name, JSONObject(m as Map<*, *>).toString()).apply()

    @Synchronized
    private fun run(ctx: Context) {
        val server = Circle.children(ctx)
        if (server == null) { offUntil = System.currentTimeMillis() + 6 * 3_600_000L; return }
        val parent = Circle.isParent(ctx)
        val circle = Circle.circleId(ctx)
        val linksKey = "child_links_$circle"
        val syncedKey = "child_synced_$circle"
        val links = links(ctx, linksKey)
        val synced = links(ctx, syncedKey)
        val members = Profile.members(ctx).toMutableList()
        val local = members.filter { it.isChild }.map { ChildSyncPlan.Local(it.id, it.name, it.birth) }
        val actions = ChildSyncPlan.plan(local, server, links, synced, parent)
        if (actions.isEmpty()) return
        var changed = false
        for (a in actions) {
            try {
                when (a) {
                    is ChildSyncPlan.Action.Upload -> {
                        val id = Circle.childAdd(ctx, a.local.name, a.local.birth)
                        links[a.local.id] = id
                        synced[a.local.id] = ChildSyncPlan.key(a.local.name, ChildSyncPlan.cleanBirth(a.local.birth))
                    }
                    is ChildSyncPlan.Action.Link -> {
                        links[a.localId] = a.serverId
                        local.firstOrNull { it.id == a.localId }?.let { synced[a.localId] = ChildSyncPlan.key(it.name, ChildSyncPlan.cleanBirth(it.birth)) }
                    }
                    is ChildSyncPlan.Action.AddLocal -> {
                        // Someone else in the family profile already has this name: don't make a second one.
                        if (members.any { Profile.sameName(it.name, a.server.name) }) continue
                        val m = FamilyMember(UUID.randomUUID().toString(), a.server.name, "ילד", a.server.birth)
                        members += m
                        links[m.id] = a.server.id
                        synced[m.id] = ChildSyncPlan.key(a.server.name, a.server.birth)
                        changed = true
                    }
                    is ChildSyncPlan.Action.UpdateLocal -> {
                        val i = members.indexOfFirst { it.id == a.localId }
                        if (i >= 0) { members[i] = members[i].copy(name = a.server.name, birth = a.server.birth); changed = true }
                        synced[a.localId] = ChildSyncPlan.key(a.server.name, a.server.birth)
                    }
                    is ChildSyncPlan.Action.UpdateServer -> {
                        Circle.childUpdate(ctx, a.serverId, a.local.name, a.local.birth)
                        synced[a.local.id] = ChildSyncPlan.key(a.local.name, ChildSyncPlan.cleanBirth(a.local.birth))
                    }
                    is ChildSyncPlan.Action.DeleteServer -> {
                        Circle.childDelete(ctx, a.serverId)
                        links.entries.firstOrNull { it.value == a.serverId }?.key?.let { links.remove(it); synced.remove(it) }
                    }
                    is ChildSyncPlan.Action.RemoveLocal -> {
                        if (members.removeAll { it.id == a.localId }) changed = true
                        links.remove(a.localId); synced.remove(a.localId)
                    }
                    is ChildSyncPlan.Action.Unlink -> { links.remove(a.localId); synced.remove(a.localId) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "action $a", e)
            }
        }
        if (changed) Profile.saveMembers(ctx, members)
        saveMap(ctx, linksKey, links)
        saveMap(ctx, syncedKey, synced)
    }
}
