package com.pqixing.intellij.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.pqixing.intellij.utils.UiUtils
import java.io.File


open class FormatAction : AnAction() {

    lateinit var project: Project

    override fun actionPerformed(e: AnActionEvent) {
        val target = e.project ?: return
        target.save()
        val moduleFile = VfsUtil.findFileByIoFile(File(target.basePath, ".idea/modules.xml"), true)
        UiUtils.addTask(100, Runnable { UiUtils.formatModule(target,moduleFile,true) })

    }
}