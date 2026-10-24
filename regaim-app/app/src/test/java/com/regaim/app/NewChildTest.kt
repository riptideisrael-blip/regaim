package com.regaim.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression: a child added from Pitzki's "add a child" without touching the chips is a child everywhere. */
class NewChildTest {
    @Test fun addedAsAChildCountsAsAChild() {
        assertTrue(FamilyMember("k", "נועה", Profile.newMemberRelation(kid = true), "2021-05-01").isChild)
    }

    @Test fun anOtherNewMemberIsNotAssumedToBeAChild() {
        assertFalse(FamilyMember("m", "דודה", Profile.newMemberRelation(kid = false), "").isChild)
    }
}
