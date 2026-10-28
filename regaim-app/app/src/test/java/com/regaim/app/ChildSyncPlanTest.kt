package com.regaim.app

import com.regaim.app.ChildSyncPlan.Action
import com.regaim.app.ChildSyncPlan.Local
import com.regaim.app.ChildSyncPlan.Server
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChildSyncPlanTest {
    private val uri = Local("l1", "אורי", "2021-12-12")
    private val noam = Local("l2", "נעם", "")

    @Test fun firstParentUploadsTheirChildren() {
        val a = ChildSyncPlan.plan(listOf(uri, noam), emptyList(), emptyMap(), emptyMap(), parent = true)
        assertEquals(listOf(Action.Upload(uri), Action.Upload(noam)), a)
    }

    @Test fun aGrandparentNeverChangesTheServer() {
        val a = ChildSyncPlan.plan(listOf(uri), emptyList(), emptyMap(), emptyMap(), parent = false)
        assertTrue(a.isEmpty())
    }

    @Test fun secondParentLinksTheSameChildAndFillsTheBirthday() {
        val s = listOf(Server("s1", "אורי", "2021-12-12"), Server("s2", "נעם", "2024-03-01"))
        val a = ChildSyncPlan.plan(listOf(uri, noam), s, emptyMap(), emptyMap(), parent = true)
        assertEquals(listOf(Action.Link("l1", "s1"), Action.Link("l2", "s2"), Action.UpdateLocal("l2", s[1])), a)
    }

    @Test fun sameNameDifferentBirthdayIsNotTheSameChild() {
        val s = listOf(Server("s1", "אורי", "2019-01-01"))
        val a = ChildSyncPlan.plan(listOf(uri), s, emptyMap(), emptyMap(), parent = true)
        assertEquals(listOf(Action.AddLocal(s[0]), Action.Upload(uri)), a)
    }

    @Test fun aChildAddedOnTheOtherPhoneArrives() {
        val s = listOf(Server("s3", "תמר", ""))
        assertEquals(listOf(Action.AddLocal(s[0])), ChildSyncPlan.plan(emptyList(), s, emptyMap(), emptyMap(), parent = false))
    }

    @Test fun myEditGoesUpTheirEditComesDown() {
        val s = Server("s1", "אורי", "2021-12-12")
        val links = mapOf("l1" to "s1")
        val synced = mapOf("l1" to ChildSyncPlan.key("אורי", "2021-12-12"))
        val renamed = uri.copy(name = "אורי רביבו")
        assertEquals(listOf(Action.UpdateServer("s1", renamed)), ChildSyncPlan.plan(listOf(renamed), listOf(s), links, synced, parent = true))
        val theirs = s.copy(birth = "2021-12-13")
        assertEquals(listOf(Action.UpdateLocal("l1", theirs)), ChildSyncPlan.plan(listOf(uri), listOf(theirs), links, synced, parent = true))
        // A non-parent's local edit doesn't win: the server's details come back.
        assertEquals(listOf(Action.UpdateLocal("l1", s)), ChildSyncPlan.plan(listOf(renamed), listOf(s), links, synced, parent = false))
    }

    @Test fun removingAndRemoved() {
        val s = Server("s1", "אורי", "2021-12-12")
        val links = mapOf("l1" to "s1")
        assertEquals(listOf(Action.DeleteServer("s1")), ChildSyncPlan.plan(emptyList(), listOf(s), links, emptyMap(), parent = true))
        assertEquals(listOf(Action.AddLocal(s)), ChildSyncPlan.plan(emptyList(), listOf(s), links, emptyMap(), parent = false))
        assertEquals(listOf(Action.RemoveLocal("l1")), ChildSyncPlan.plan(listOf(uri), listOf(s.copy(deleted = true)), links, emptyMap(), parent = false))
        assertEquals(listOf(Action.Unlink("l1")), ChildSyncPlan.plan(listOf(uri), emptyList(), links, emptyMap(), parent = false))
    }

    @Test fun nothingToDoWhenInStep() {
        val s = Server("s1", "אורי", "2021-12-12")
        val synced = mapOf("l1" to ChildSyncPlan.key("אורי", "2021-12-12"))
        assertTrue(ChildSyncPlan.plan(listOf(uri), listOf(s), mapOf("l1" to "s1"), synced, parent = true).isEmpty())
    }

    @Test fun birthdaysAreCleaned() {
        assertEquals("2021-12-12", ChildSyncPlan.cleanBirth(" 2021-12-12 "))
        assertEquals("", ChildSyncPlan.cleanBirth("12/12/2021"))
        assertEquals("", ChildSyncPlan.cleanBirth("2021-13-01"))
    }
}
