package com.pqixing.modularization.android.dps

import com.pqixing.Tools
import com.pqixing.git.Components
import com.pqixing.help.MavenPom
import com.pqixing.help.XmlHelper
import com.pqixing.modularization.android.AndroidPlugin
import com.pqixing.modularization.base.BasePlugin
import com.pqixing.modularization.manager.ManagerExtends
import com.pqixing.modularization.manager.ManagerPlugin
import com.pqixing.modularization.manager.ProjectManager
import com.pqixing.modularization.maven.VersionManager
import com.pqixing.tools.FileUtils
import com.pqixing.tools.TextUtils
import java.io.File
import java.net.URL

class DpsManager(val plugin: AndroidPlugin) {
    companion object {
        val pomCache: HashMap<String, MavenPom> = HashMap()
        /**
         * 获取仓库aar中，exclude的传递
         */
        fun getPom(branch: String, module: String, version: String): MavenPom {
            val plugin = BasePlugin.getPlugin(ManagerPlugin::class.java)!!
            val extends = plugin.getExtends(ManagerExtends::class.java)
            val groupMaven = extends.groupMaven
            val group = "${extends.groupName}.$branch"
            val pomUrl = "$groupMaven/${group.replace(".", "/")}/$module/$version/$module-$version.xml"
            val pomKey = TextUtils.numOrLetter(pomUrl)
            var pom = pomCache[pomKey]
            if (pom != null) return pom

            val pomDir = File(plugin.getGradle().gradleHomeDir, "pomCache")
            val pomFile = File(pomDir, pomKey)
            pom = if (pomFile.exists()) XmlHelper.parsePomEclude(FileUtils.readText(pomFile)!!, extends.groupName)
            else {
                val ponTxt = URL(pomUrl).readText()
                FileUtils.writeText(pomFile, ponTxt)
                XmlHelper.parsePomEclude(ponTxt, extends.groupName)
            }
            pomCache[pomKey] = pom
            return pom
        }
    }

    //组件工程
    val components = ProjectManager.allComponents[plugin.project.name]!!
    val compileModel = plugin.projectInfo?.dependentModel ?: "mavenOnly"
    val managerExtends = BasePlugin.getPlugin(ManagerPlugin::class.java)?.getExtends(ManagerExtends::class.java)!!

    //处理依赖
    fun resolveDps(dpsExt: DpsExtends): String {
        val excludes: HashSet<String> = HashSet()
        val includes: ArrayList<String> = ArrayList()
        val loseList = mutableListOf<String>()

        onSelfCompile(includes, excludes)

        dpsExt.apis.forEach { dpc ->
            val compile = when (compileModel) {
                "localOnly" -> onLocalCompile(dpc, includes, excludes)
                "localFirst" -> onLocalCompile(dpc, includes, excludes) || onMavenCompile(dpc, includes, excludes)
                "mavenFirst" -> onMavenCompile(dpc, includes, excludes) || onLocalCompile(dpc, includes, excludes)
                else -> onMavenCompile(dpc, includes, excludes)
            }
            if (!compile) loseList.add(dpc.moduleName)
            val newVersion = VersionManager.getVersion(dpc.branch, dpc.moduleName, "+")
            Tools.println("${dpc.moduleName} config version : ${dpc.version} -> last version : ${newVersion.second} match branch : ${newVersion.first}")
        }

        //如果是dev分支运行
        if (plugin.APP_TYPE != Components.TYPE_APPLICATION && plugin.BUILD_TYPE == Components.TYPE_APPLICATION)
            dpsExt.devApis.forEach { dpc ->
                val compile = when (compileModel) {
                    "localOnly" -> onLocalCompile(dpc, includes, excludes)
                    "localFirst" -> onLocalCompile(dpc, includes, excludes) || onMavenCompile(dpc, includes, excludes)
                    "mavenFirst" -> onMavenCompile(dpc, includes, excludes) || onLocalCompile(dpc, includes, excludes)
                    else -> onMavenCompile(dpc, includes, excludes)
                }

                val newVersion = VersionManager.getVersion(dpc.branch, dpc.moduleName, "+")
                Tools.println("${dpc.moduleName} config version : ${dpc.version} -> last version : ${newVersion.second} match branch : ${newVersion.first}")
                if (!compile) loseList.add(dpc.moduleName)
            }

        /**
         * 缺失了部分依赖
         */
        if (loseList.isNotEmpty()) {
            if (plugin.projectInfo.allowLose) Tools.println("resolveDps -> lose dps -> $loseList")
            else Tools.printError("resolveDps -> lose dps -> $loseList")
        }
        val sb = java.lang.StringBuilder("dependencies { \n")
        includes.forEach { sb.append(it).append("\n") }
        sb.append("}\n")
                .append("configurations { \n")
                .append(excludeStr("all*.exclude ", excludes.map { XmlHelper.strToPair(it) }.toSet()))
                .append("}\n")
        return sb.toString()
    }

    /**
     * 处理自身的依赖，主要针对Library_api类型
     */
    private fun onSelfCompile(includes: ArrayList<String>, excludes: HashSet<String>) {
        //如果是api模块作为独立运行时，移除api模块代码（因为主模块默认已经包含了api的代码）
        if (components.type == Components.TYPE_LIBRARY_API && plugin.BUILD_TYPE == Components.TYPE_APPLICATION) {
            addBranchExclude(components.lastLog.branch, "${components.name}_api", excludes, 0)
        }
    }

    private fun onMavenCompile(dpc: DpComponents, includes: ArrayList<String>, excludes: HashSet<String>): Boolean {
        var compile = false
        //如果是App类型工程，则以app直接依赖的版本为准，忽略依赖传递中带来的版本变化
        val config = if (plugin.APP_TYPE == Components.TYPE_APPLICATION) "force = true" else ""
        //如果是Api类型的模块，只依赖api工程，不直接依赖模块代码
        if (dpc.type == Components.TYPE_LIBRARY_API) {
            compile = addMavenCompile(DpsExtends.SCOP_API, dpc.branch, "${dpc.moduleName}_api", dpc.version, includes, excludes, HashSet(), config) and compile

            //如果是作为本地运行时，则把编译对应的模块
            if (plugin.BUILD_TYPE == Components.TYPE_APPLICATION) {
                compile = addMavenCompile(DpsExtends.SCOP_RUNTIME, dpc.branch, dpc.moduleName, dpc.version, includes, excludes, HashSet(), config) and compile
            }

        } else {//如果是普通模块，直接依赖模块代码
            compile = addMavenCompile(DpsExtends.SCOP_API, dpc.branch, dpc.moduleName, dpc.version, includes, excludes, HashSet(), config) and compile
        }
        return compile
    }

    /**
     * 添加一个仓库依赖
     */
    private fun addMavenCompile(scope: String, branch: String, module: String, version: String, includes: ArrayList<String>, excludes: HashSet<String>, dpExcludes: HashSet<Pair<String?, String?>>, config: String = ""): Boolean {
        val dpVersion = VersionManager.getVersion(branch, module, version)
        if (dpVersion.first.isEmpty()) return false
        includes.add("$scope ('${managerExtends.groupName}.${dpVersion.first}:$module:${dpVersion.second}') { ${excludeStr(excludes = dpExcludes)} \n $config }")
        addBranchExclude(dpVersion.first, module, excludes)
        excludes.addAll(getPom(dpVersion.first, module, dpVersion.second).allExclude)
        return true
    }

    /**
     * 检查是否存在其他分支的版本，如果存在，添加到exclude中
     */
    private fun addBranchExclude(compileBranch: String, moduleName: String, excludes: HashSet<String>, pointer: Int = 1) {
        with(managerExtends.matchingFallbacks) {
            val start = indexOf(compileBranch) + pointer
            for (i in start until size) {
                val b = get(i)
                if (VersionManager.checkBranchVersion(b, moduleName)) {
                    excludes.add(XmlHelper.pairToStr("${managerExtends.groupName}.$b", moduleName))
                }
            }
        }
    }

    /**
     * 生成exlude字符串
     */
    private fun excludeStr(prefix: String = "exclude", excludes: Set<Pair<String?, String?>>): String {
        val sb = StringBuilder()
        excludes.forEach {
            sb.append("$prefix (")
            if (it.first != null) sb.append("group : '${it.first}', ")
            if (it.second != null) sb.append("module : '${it.second}', ")
            sb.deleteCharAt(sb.length - 1)
            sb.append(") \n")
        }
        return sb.toString()
    }

    private fun onLocalCompile(dpc: DpComponents, includes: ArrayList<String>, excludes: HashSet<String>): Boolean {
        val localProject = plugin.project.rootProject.allprojects.find { it.name == dpc.moduleName }
                ?: return false
        val branch = components.lastLog.branch
        //如果该依赖没有本地导入，不进行本地依赖
        val dpComponents = ProjectManager.checkProject(localProject, plugin.projectInfo!!)
                ?: return false
        dpc.localCompile = true
        if (dpComponents.type == Components.TYPE_LIBRARY_API) {
            includes.add("${DpsExtends.SCOP_RUNTIME}  project(':${dpc.moduleName}') { ${excludeStr(excludes = dpc.excludes)} }")
            addBranchExclude(branch, dpc.moduleName, excludes)
            //添加对api的maven仓库的编译依赖，只有编译时期使用
            addMavenCompile(DpsExtends.SCOP_COMPILEONLY, dpc.branch, "${dpc.moduleName}_api", dpc.version, includes, excludes, HashSet(), "force = true")
        } else {
            includes.add("${DpsExtends.SCOP_API}  project(':${dpc.moduleName}')  { ${excludeStr(excludes = dpc.excludes)} }")
            addBranchExclude(branch, dpc.moduleName, excludes)
        }
        return true
    }


}