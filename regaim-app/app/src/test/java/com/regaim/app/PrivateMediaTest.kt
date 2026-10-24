package com.regaim.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateMediaTest {
    private val dir = "/data/user/0/com.regaim.app/files/moments_media"

    @Test fun onlyFilesInTheAppsOwnFolder() {
        assertTrue(PrivateMedia.inside("$dir/regaim_1.mp4", dir))
        assertTrue(PrivateMedia.inside("file://$dir/regaim_1.mp4", dir))
        // A gallery moment, another folder, or a way out with ".." is never deleted as "private".
        assertFalse(PrivateMedia.inside("content://media/external/video/media/12", dir))
        assertFalse(PrivateMedia.inside("/storage/emulated/0/Movies/Moments/regaim_1.mp4", dir))
        assertFalse(PrivateMedia.inside("$dir/../shared_prefs/x.xml", dir))
        assertFalse(PrivateMedia.inside("${dir}_other/x.mp4", dir))
        assertFalse(PrivateMedia.inside("", dir))
    }
}
