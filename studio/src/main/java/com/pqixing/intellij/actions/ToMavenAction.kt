package com.pqixing.intellij.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.pqixing.EnvKeys
import com.pqixing.intellij.adapter.JListInfo
import com.pqixing.intellij.ui.ToMavenDialog
import com.pqixing.intellij.utils.TaskCallBack
import com.pqixing.intellij.utils.GradleUtils

class ToMavenAction : AnAction() {
    lateinit var project: Project
    override fun update(e: AnActionEvent) {
        e?.presentation?.isEnabledAndVisible = QToolGroup.isModulariztionProject(e?.project)
    }
    override fun actionPerformed(e: AnActionEvent) {
        project = e.project ?: return

        val module = e.getData(DataKey.create<Module>("module"))
        val moduleName = module?.name ?: ""
        val projectMode = /*"ProjectViewPopup".equals(place)||*/"MainMenu" == e.place || module == null || project.name == moduleName;
        var filters = if (projectMode) null else ModuleRootManager.getInstance(module!!).dependencies.map { it.name }

        //需要ToMaven的模块
        val tModules = ModuleManager.getInstance(project).sortedModules
                .filter { it.name != project.name && (filters == null || it.name == moduleName || filters.contains(it.name)) }
                .map { JListInfo(it.name, "", 0, it.name == moduleName || it.name == "${moduleName}_api") }

        val dialog = ToMavenDialog(project,tModules, moduleName);
        dialog.setOnOk { toMaven(dialog, tModules) }
        dialog.pack()
        dialog.isVisible = true
    }

    private fun toMaven(dialog: ToMavenDialog, tModules: List<JListInfo>) = object : TaskCallBack {
        var i = -1
        var check = false//是否需要校验结果
        var runTaskId = ""
        var excute = 0
        var envs = GradleUtils.defEnvs.toMutableMap().apply {
            put(EnvKeys.toMavenUnCheck, dialog.unCheckCode)
        }

        override fun onTaskEnd(success: Boolean, result: String?) {
            if (check) {//检查上传的任务是否正确
                val jListInfo = tModules[i]
                jListInfo.staue = if (success) 1 else 3
                jListInfo.log = result ?: ""
                //失败是展示结果
                dialog.updateUI(!success)

                if (!success) return
            }
            if (i >= tModules.size - 1) {
                dialog.updateUI(false)
                return
            }//执行完毕
            val info = tModules[++i]
            if (info.select && info.staue != 1) {
                check = true
                excute++
                runTaskId = System.currentTimeMillis().toString()
                info.staue = 2//正在执行
                GradleUtils.runTask(project, listOf(":${info.title}:clean", ":${info.title}:ToMaven"), activateToolWindowBeforeRun = excute == 1, envs = envs, runTaskId = runTaskId, callback = this)
            } else {
                check = false
                runTaskId = ""
                this.onTaskEnd(false, null)//循环运行
            }
        }
    }.onTaskEnd(false, null)
}