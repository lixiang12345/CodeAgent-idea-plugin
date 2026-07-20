package com.codeagent.plugin.context

import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.ScalarIndexExtension
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import java.util.Collections

class CodeAgentWorkspaceIndex : ScalarIndexExtension<String>() {
    override fun getName(): ID<String, Void> = NAME

    override fun getIndexer(): DataIndexer<String, Void, FileContent> = DataIndexer { input ->
        Collections.singletonMap(input.file.path, null)
    }

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getInputFilter(): FileBasedIndex.InputFilter = FileBasedIndex.InputFilter(::accept)

    override fun dependsOnFileContent(): Boolean = false

    override fun getVersion(): Int = VERSION

    private fun accept(file: VirtualFile): Boolean {
        if (file.isDirectory || !file.isInLocalFileSystem || file.length > MAX_FILE_BYTES || file.fileType.isBinary) return false
        if (FileTypeRegistry.getInstance().isFileOfType(file, com.intellij.openapi.fileTypes.UnknownFileType.INSTANCE)) return false
        val path = file.path.replace('\\', '/')
        return IGNORED_SEGMENTS.none { "/$it/" in "/$path/" }
    }

    companion object {
        val NAME: ID<String, Void> = ID.create("CodeAgent.workspace.files")
        private const val VERSION = 2
        private const val MAX_FILE_BYTES = 2L * 1024 * 1024
        private val IGNORED_SEGMENTS = setOf(".git", ".idea", ".contextengine", "node_modules", "build", "dist", "out")
    }
}
