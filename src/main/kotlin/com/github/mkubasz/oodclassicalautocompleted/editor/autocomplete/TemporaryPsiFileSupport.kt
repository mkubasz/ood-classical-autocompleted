package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory

internal object TemporaryPsiFileSupport {

    fun createTemporaryPsiFile(
        project: Project,
        text: String,
        filePath: String?,
        defaultFileName: String = DEFAULT_TEMP_FILE_NAME,
    ): PsiFile? {
        val fileName = temporaryFileName(filePath, defaultFileName)
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
        if (fileType === UnknownFileType.INSTANCE) return null

        return PsiFileFactory.getInstance(project).createFileFromText(fileName, fileType, text)
    }

    fun temporaryFileName(
        filePath: String?,
        defaultFileName: String = DEFAULT_TEMP_FILE_NAME,
    ): String {
        if (!filePath.isNullOrBlank()) {
            return filePath.substringAfterLast('/').ifBlank { defaultFileName }
        }
        return defaultFileName
    }

    private const val DEFAULT_TEMP_FILE_NAME = "temp.py"
}
