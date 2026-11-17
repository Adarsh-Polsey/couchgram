package tv.couchgram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandsTest {
    @Test
    fun playNeedsAReply() {
        assertEquals(Command.Play(42L), Commands.parse("/play", 42L))
        assertEquals(Command.PlayHint, Commands.parse("/play", null))
        assertEquals(Command.Play(7L), Commands.parse("  /PLAY now please ", 7L))
    }

    @Test
    fun otherCommands() {
        assertEquals(Command.Stop, Commands.parse("/stop", null))
        assertEquals(Command.Stop, Commands.parse("/back", 3L))
        assertEquals(Command.Storage, Commands.parse("/storage", null))
        assertEquals(Command.Storage, Commands.parse("/storage@CouchgramBot", null))
        assertEquals(Command.ClearCache, Commands.parse("/clearcache", null))
        assertEquals(Command.ClearCache, Commands.parse("/ClearCache", 9L))
        assertEquals(Command.ClearCache, Commands.parse("/clear", null))
    }

    @Test
    fun plainTextIsNotACommand() {
        assertNull(Commands.parse("play it", 1L))
        assertNull(Commands.parse("/playlist", 1L))
        assertNull(Commands.parse("/", null))
        assertNull(Commands.parse("", null))
        assertNull(Commands.parse("💾 TV storage · 3.0 GB free", null))
    }
}
