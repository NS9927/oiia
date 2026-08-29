package net.posdaca.oiia.core.preview

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import net.posdaca.oiia.core.files.ResourceFiles

internal object PreviewNavigation {
    fun open(project: Project, path: String?, line: Int = 0) {
        val vf = ResourceFiles.toVirtualFile(path ?: return) ?: return
        if (line > 0) {
            OpenFileDescriptor(project, vf, line - 1, 0).navigate(true)
        } else {
            OpenFileDescriptor(project, vf).navigate(true)
        }
    }
}