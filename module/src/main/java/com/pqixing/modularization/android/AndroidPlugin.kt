package com.pqixing.modularization.android


import com.android.build.gradle.BaseExtension
import com.pqixing.model.Module
import com.pqixing.modularization.Keys
import com.pqixing.modularization.android.dps.DpsExtendsCompat
import com.pqixing.modularization.android.tasks.BuildApkTask
import com.pqixing.modularization.android.tasks.DpsAnalysisTask
import com.pqixing.modularization.base.BasePlugin
import com.pqixing.modularization.setting.ImportPlugin
import com.pqixing.tools.FileUtils
import com.pqixing.tools.TextUtils
import groovy.lang.Closure
import org.gradle.api.Project
import org.gradle.api.Task
import java.io.File

open class AndroidPlugin : BasePlugin() {

    /**
     * application类型工程
     */
    val isApp: Boolean
        get() = module.isApplication

    /**
     * 作为app运行  library工程也可以
     */
    val buildAsApp: Boolean
        get() = ImportPlugin.findArgs(project).runAsApp(module)

    lateinit var module: Module

    override val ignoreFields: Set<String> = emptySet()

    override fun linkTask(): List<Class<out Task>> = mutableListOf(DpsAnalysisTask::class.java, BuildApkTask::class.java)
    var doAfterList: MutableList<Runnable> = mutableListOf()
    override fun apply(project: Project) {
        project.extensions.create("innerDps", DpsExtendsCompat::class.java)
        //查找当前项目对应的模块和依赖关系
        val args = ImportPlugin.findArgs(project)
        module = args.manifest.findModule(project.name)!!
        super.apply(project)
        //如果是空同步，不做任何处理
        extHelper.setExtMethod(project, "doAfterEvaluate") { if (it is Closure<*>) doAfterList.add(it) }
        //在Android工程之前，执行
        project.afterEvaluate { doAfterList.forEach { r -> r.run() } }
        //根据情况进行不同的Android插件依赖
        project.apply(mapOf<String, String>("plugin" to if (buildAsApp) Keys.NAME_APP else Keys.NAME_LIBRARY))

        //如果是Library模块运行，设置ApplicationId
        if (buildAsApp && !isApp) {
            extHelper.setApplicationId(project, "com.${TextUtils.letter(module.getBranch(), "libraryrun")}.${TextUtils.letter(project.name, "app")}".toLowerCase())
        }

        doAfterList.add(Runnable {
            //开始注解切入
            if (buildAsApp && module.transform) (project.extensions.getByName("android") as? BaseExtension)?.registerTransform(PqxTransform())
            project.tasks.find { t -> t.name == "clean" }?.doLast { FileUtils.delete(File(project.projectDir, "build")) }
        })

    }
}