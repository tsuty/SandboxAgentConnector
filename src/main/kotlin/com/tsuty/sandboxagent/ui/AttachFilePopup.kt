package com.tsuty.sandboxagent.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.util.gotoByName.ChooseByNamePopup
import com.intellij.ide.util.gotoByName.ChooseByNamePopupComponent
import com.intellij.ide.util.gotoByName.GotoFileModel
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopupStepEx
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileSystemItem
import com.intellij.util.ui.StatusText
import javax.swing.Icon
import javax.swing.JComponent

object AttachFileUtils {
    fun getProjectBaseDir(project: Project): VirtualFile? {
        val basePath = project.basePath ?: return null
        return LocalFileSystem.getInstance().findFileByPath(basePath)
    }

    fun isInsideProject(file: VirtualFile, project: Project): Boolean {
        val projectDir = getProjectBaseDir(project) ?: return false
        return VfsUtilCore.isAncestor(projectDir, file, false)
    }

    fun getProjectRelativePath(file: VirtualFile, project: Project): String {
        val projectDir = getProjectBaseDir(project)
        if (projectDir != null) {
            val rel = VfsUtilCore.getRelativePath(file, projectDir)
            if (rel != null) return if (rel.isEmpty()) "." else rel
        }
        val basePath = project.basePath
        if (basePath != null && file.path.startsWith(basePath)) {
            return file.path.removePrefix(basePath).removePrefix("/").ifEmpty { "." }
        }
        return file.name
    }

    fun collectOpenFiles(project: Project): List<VirtualFile> {
        if (project.isDisposed) return emptyList()
        val manager = FileEditorManager.getInstance(project)
        val selectedFiles = manager.selectedFiles.filter { it.isValid && isInsideProject(it, project) }
        val otherOpenFiles = manager.openFiles.filter { it.isValid && isInsideProject(it, project) && it !in selectedFiles }
        return (selectedFiles + otherOpenFiles).distinctBy { it.path }
    }

    fun collectOpenDirectories(project: Project, openFiles: List<VirtualFile>): List<VirtualFile> {
        return openFiles.mapNotNull { file ->
            val parent = file.parent
            if (parent != null && parent.isValid && isInsideProject(parent, project)) parent else null
        }.distinctBy { it.path }
    }
}

sealed class AttachItem {
    object Search : AttachItem()
    data class FileItem(val file: VirtualFile, val name: String, val relativePath: String) : AttachItem()
    data class DirectoryItem(val dir: VirtualFile, val name: String, val relativePath: String) : AttachItem()
}

class AttachFilePopupStep(
    private val items: List<AttachItem>,
    private val onChosen: (VirtualFile) -> Unit,
    private val onSearch: () -> Unit,
) : BaseListPopupStep<AttachItem>("Attach File or Directory", items), ListPopupStepEx<AttachItem> {

    override fun getTextFor(value: AttachItem): String = when (value) {
        is AttachItem.Search -> "Select from file search..."
        is AttachItem.FileItem -> value.name
        is AttachItem.DirectoryItem -> value.name
    }

    override fun getSecondaryTextFor(value: AttachItem): String? = when (value) {
        is AttachItem.Search -> null
        is AttachItem.FileItem -> value.relativePath
        is AttachItem.DirectoryItem -> value.relativePath
    }

    override fun getIconFor(value: AttachItem): Icon = when (value) {
        is AttachItem.Search -> AllIcons.Actions.Search
        is AttachItem.FileItem -> value.file.fileType.icon
        is AttachItem.DirectoryItem -> AllIcons.Nodes.Folder
    }

    override fun getSeparatorAbove(value: AttachItem): ListSeparator? {
        val index = items.indexOf(value)
        if (index < 0) return null
        if (value is AttachItem.FileItem && (index == 0 || items[index - 1] !is AttachItem.FileItem)) {
            return ListSeparator("Open Files")
        }
        if (value is AttachItem.DirectoryItem && (index == 0 || items[index - 1] !is AttachItem.DirectoryItem)) {
            return ListSeparator("Directories")
        }
        return null
    }

    override fun getTooltipTextFor(value: AttachItem): String? = when (value) {
        is AttachItem.Search -> "Search and attach files in project"
        is AttachItem.FileItem -> value.file.path
        is AttachItem.DirectoryItem -> value.dir.path
    }

    override fun setEmptyText(emptyText: StatusText) {
        emptyText.text = "No selectable items"
    }

    override fun isSelectable(value: AttachItem): Boolean = true

    override fun onChosen(selectedValue: AttachItem, finalChoice: Boolean): PopupStep<*>? {
        if (finalChoice) {
            when (selectedValue) {
                is AttachItem.Search -> onSearch()
                is AttachItem.FileItem -> onChosen(selectedValue.file)
                is AttachItem.DirectoryItem -> onChosen(selectedValue.dir)
            }
        }
        return FINAL_CHOICE
    }
}

object AttachFilePopup {
    fun show(anchor: JComponent, project: Project, onFileChosen: (VirtualFile) -> Unit) {
        val openFiles = AttachFileUtils.collectOpenFiles(project)
        val openDirs = AttachFileUtils.collectOpenDirectories(project, openFiles)

        val items = buildList {
            add(AttachItem.Search)
            openFiles.forEach { file ->
                add(AttachItem.FileItem(file, file.name, AttachFileUtils.getProjectRelativePath(file, project)))
            }
            openDirs.forEach { dir ->
                val name = dir.name.ifEmpty { project.name }
                add(AttachItem.DirectoryItem(dir, name, AttachFileUtils.getProjectRelativePath(dir, project)))
            }
        }

        val step = AttachFilePopupStep(
            items = items,
            onChosen = onFileChosen,
            onSearch = { openFileSearch(project, onFileChosen) },
        )

        val popup = JBPopupFactory.getInstance().createListPopup(step)
        popup.showUnderneathOf(anchor)
    }

    fun openFileSearch(project: Project, onFileChosen: (VirtualFile) -> Unit) {
        if (project.isDisposed) return
        val model = ProjectOnlyGotoFileModel(project)
        val popup = ChooseByNamePopup.createPopup(project, model, null as PsiElement?)
        popup.setShowListForEmptyPattern(true)
        popup.invoke(object : ChooseByNamePopupComponent.MultiElementsCallback() {
            override fun elementsChosen(elements: List<Any>) {
                for (element in elements) {
                    val file = extractVirtualFile(element)
                    if (file != null && AttachFileUtils.isInsideProject(file, project)) {
                        onFileChosen(file)
                    }
                }
            }
        }, ModalityState.current(), true)
    }

    class ProjectOnlyGotoFileModel(project: Project) : GotoFileModel(project) {
        override fun getCheckBoxName(): String? = null
        override fun loadInitialCheckBoxState(): Boolean = false
        override fun saveInitialCheckBoxState(state: Boolean) = Unit
    }

    fun extractVirtualFile(element: Any?): VirtualFile? = when (element) {
        is PsiFileSystemItem -> element.virtualFile
        is PsiElement -> element.containingFile?.virtualFile
        is VirtualFile -> element
        else -> null
    }
}
