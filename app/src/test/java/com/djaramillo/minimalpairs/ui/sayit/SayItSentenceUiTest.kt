package com.djaramillo.minimalpairs.ui.sayit

import com.djaramillo.minimalpairs.domain.model.SayItWord
import com.djaramillo.minimalpairs.storage.DataFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure decisions of the Say-it screens: which page a given state belongs
 * on, whether entering the mode again starts a new run, and whether a word has
 * an IPA line at all.
 */
class SayItSentenceUiTest {

    private fun word(id: String, ipa: String? = null) =
        SayItWord(id = id, word = id, sentence = "A sentence with $id in it.", ipa = ipa)

    @Test
    fun ipa_line_is_drawn_only_when_there_is_one() {
        // The coach writes "ipa": "" for a word it has no transcription for, and
        // an absent key decodes to null: both must leave the line out, or every
        // sentence of every run carries a bare "//" under it.
        assertNull(ipaLine(null))
        assertNull(ipaLine(""))
        assertNull(ipaLine("   "))
        assertEquals("ɪmˈpɪərɪəlɪst", ipaLine("ɪmˈpɪərɪəlɪst"))
        assertEquals("ɪmˈpɪərɪəlɪst", ipaLine("  ɪmˈpɪərɪəlɪst  "))
    }

    @Test
    fun a_run_with_no_active_word_is_empty_once_the_folder_has_been_read() {
        assertFalse(SayItSentenceUi().empty)
        assertFalse(SayItSentenceUi(loading = true, words = emptyList()).empty)
        assertTrue(SayItSentenceUi(loading = false).empty)
        assertFalse(SayItSentenceUi(loading = false, words = listOf(word("a"))).empty)
        // Tutor words are outside the run, so they do not make it non-empty:
        // the empty page lists them rather than the done page, which a run with
        // nothing to drill never reaches.
        assertTrue(SayItSentenceUi(loading = false, tutor = listOf(word("a"))).empty)
    }

    @Test
    fun only_a_finished_blocked_or_empty_run_is_thrown_away_on_re_entry() {
        val run = SayItSentenceUi(loading = false, words = listOf(word("a"), word("b")))
        // Mid-run: re-entry here is a rotation, and a sitting must survive it.
        assertFalse(run.stale)
        assertFalse(run.copy(index = 1, recording = true).stale)
        // Still reading the folder: the run being set up is not a stale one.
        assertFalse(SayItSentenceUi().stale)
        // The three ends of a run, each of which must open the folder again so a
        // sayit.zip that arrived meanwhile is seen.
        assertTrue(run.copy(done = true).stale)
        assertTrue(SayItSentenceUi(loading = false).stale)
        assertTrue(run.copy(folderProblem = DataFolder.Status.PermissionLost).stale)
    }
}
