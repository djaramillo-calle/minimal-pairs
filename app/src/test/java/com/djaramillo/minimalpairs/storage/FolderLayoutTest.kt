package com.djaramillo.minimalpairs.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderLayoutTest {
    @Test
    fun documents_gets_the_subfolder() {
        assertEquals("MinimalPairs", FolderLayout.subfolderFor("Documents"))
        assertEquals("MinimalPairs", FolderLayout.subfolderFor("documents"))
        assertEquals("MinimalPairs", FolderLayout.subfolderFor(" Documents "))
    }

    @Test
    fun other_folders_are_used_as_is() {
        assertNull(FolderLayout.subfolderFor("MinimalPairs"))
        assertNull(FolderLayout.subfolderFor("Download"))
        assertNull(FolderLayout.subfolderFor("Documents2"))
        assertNull(FolderLayout.subfolderFor(null))
        assertNull(FolderLayout.subfolderFor(""))
    }

    @Test
    fun display_path_from_tree_document_id() {
        assertEquals("Documents/MinimalPairs", FolderLayout.displayPath("primary:Documents", "MinimalPairs"))
        assertEquals("Documents/MinimalPairs", FolderLayout.displayPath("primary:Documents/MinimalPairs", null))
        assertEquals("1234-5678/Music", FolderLayout.displayPath("1234-5678:Music", null))
        assertEquals("(root)/MinimalPairs", FolderLayout.displayPath("primary:", "MinimalPairs"))
        assertEquals("weird", FolderLayout.displayPath("weird", null))
        assertEquals("MinimalPairs", FolderLayout.displayPath(null, "MinimalPairs"))
        assertEquals("", FolderLayout.displayPath(null, null))
    }

    @Test
    fun session_file_names() {
        assertEquals("20260911T070211Z.json", FolderLayout.sessionFileName("20260911T070211Z"))
        assertEquals("20260911T070211Z", FolderLayout.sessionIdFromFileName("20260911T070211Z.json"))
        assertNull(FolderLayout.sessionIdFromFileName("state.json"))
        assertNull(FolderLayout.sessionIdFromFileName("20260911T070211Z.json.tmp"))
        assertNull(FolderLayout.sessionIdFromFileName("2026-09-11T07:02:11Z.json"))
    }
}
