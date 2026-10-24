package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CharacterIndexTest {
    private fun setup(n: Int): Pair<File, List<SavedCharacter>> {
        val d = Files.createTempDirectory("chars").toFile()
        val list = (1..n).map { i ->
            File(d, "char_$i.jpg").writeBytes(byteArrayOf(i.toByte()))
            SavedCharacter("id$i", "m$i", "name$i", "char_$i.jpg", 1000L + i, "b")
        }
        return d to list
    }

    @Test fun emptyStartWhenNothingSaved() {
        val d = Files.createTempDirectory("chars").toFile()
        assertEquals(0, CharacterIndex(d).load()!!.size)
    }

    @Test fun writeThenLoad() {
        val (d, list) = setup(3)
        assertTrue(CharacterIndex(d).write(list))
        assertEquals(3, CharacterIndex(d).load()!!.size)
    }

    @Test fun brokenListIsRestoredFromTheCopy() {
        val (d, list) = setup(3)
        CharacterIndex(d).write(list)
        File(d, "characters.json").writeText("[{\"id\":") // cut halfway
        val back = CharacterIndex(d).load()
        assertEquals(3, back!!.size)
        // ...and the main list is repaired.
        assertEquals(3, CharacterIndex(d).load()!!.size)
    }

    @Test fun bothBrokenMeansUnknownNotEmpty() {
        val (d, list) = setup(2)
        CharacterIndex(d).write(list)
        File(d, "characters.json").writeText("garbage")
        File(d, "characters.json.bak").writeText("garbage")
        // null = "can't tell": the store refuses to write rather than replacing everything with one new character.
        assertNull(CharacterIndex(d).load())
        assertTrue(d.listFiles()!!.any { it.name.startsWith("characters.json.bad") })
    }

    @Test fun charactersWhosePictureIsGoneAreSkipped() {
        val (d, list) = setup(2)
        CharacterIndex(d).write(list)
        File(d, "char_1.jpg").delete()
        assertEquals(listOf("id2"), CharacterIndex(d).load()!!.map { it.id })
    }
}
