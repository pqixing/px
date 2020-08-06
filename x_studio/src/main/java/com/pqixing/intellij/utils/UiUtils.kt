package com.pqixing.intellij.utils

import com.android.ddmlib.IDevice
import com.android.tools.apk.analyzer.AaptInvoker
import com.android.tools.apk.analyzer.AndroidApplicationInfo
import com.android.tools.idea.explorer.adbimpl.AdbShellCommandsUtil
import com.android.tools.idea.run.deployment.DeviceGet
import com.android.tools.idea.sdk.AndroidSdks
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.impl.BackgroundableProcessIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.*
import com.pqixing.creator.utils.LogWrap
import com.pqixing.help.XmlHelper
import com.pqixing.intellij.ui.NewImportDialog
import com.pqixing.tools.PropertiesUtils.readProperties
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.util.*
import javax.swing.JComponent
import javax.swing.TransferHandler

object UiUtils : VirtualFileListener, Runnable {

    val IDE_PROPERTIES = ".idea/caches/import.properties"
    val IML_PROPERTIES = ".idea/caches/iml.properti"
    val BUILD_PROPERTIES = ".idea/caches/build.log"
    val ftModules = mutableMapOf<String, Boolean?>()
    val lock = Object()
    val tasks: LinkedList<Pair<Long, Runnable>> = LinkedList()

    init {
        LocalFileSystem.getInstance().addVirtualFileListener(this)
        Thread(this, "uiThread").start()
    }

    fun onLockChange(l: Boolean) = try {
        synchronized(lock) {
            if (l) lock.wait() else lock.notifyAll()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    fun addTask(sleep: Long = 0, task: Runnable) {
        tasks.add(sleep to task)
        onLockChange(false)
    }

    override fun run() {
        while (true) {
            if (tasks.isEmpty()) onLockChange(true)
            val t = tasks.pollFirst()
            if (t != null) {
                if (t.first > 0) Thread.sleep(t.first)
                t.second.run()
            }
        }
    }

    override fun contentsChanged(event: VirtualFileEvent) {
        if (event.fileName != "modules.xml" || !event.file.path.endsWith(".idea/modules.xml")) return
        val moduleXml = event.file
        val target = ProjectManager.getInstance().openProjects.find { moduleXml.path.startsWith(it.basePath ?: "") }
                ?: return
        target.save()

        //格式化iml文件,//1.5秒后再次检测，防止格式化不生效
        if (formatModule(target, moduleXml)) addTask(2000, Runnable {
            formatModule(target, moduleXml)
            target.save()
        })
    }


    fun checkIfFormat(target: Project?): Boolean {
        target ?: return false
        var format = ftModules[target.basePath]
        if (format == null) {
            val properties = readProperties(File(target.basePath, IDE_PROPERTIES))
            format = "Y" == properties.getProperty(NewImportDialog.FORMAT_KEY, "Y")
            ftModules[target.basePath!!] = format
        }
        return format
    }

    fun formatModule(target: Project, moduleXml: VirtualFile?, formatFoce: Boolean = false): Boolean {
        moduleXml ?: return false
        val txtLines = mutableListOf<String>()
        val tag = "<!--end-->"
        if (!formatFoce) {
            if (checkIfFormat(target)) return false

            val ins = moduleXml.inputStream.reader()
            txtLines.addAll(ins.readLines())
            ins.close()
            if (txtLines.lastOrNull()?.endsWith(tag) == true) return false
        }

        val manifest = XmlHelper.loadManifest(target.basePath) ?: return false
        invokeLaterOnWriteThread(Runnable {
            target.save()
            val defGroup = "apis"
            val iml = ".iml"
            val rex = Regex("group=\".*\"")
            val groups = manifest.allModules().map {
                var path = if (it.attach()) defGroup else it.path.substringBeforeLast("/")
                if (path.startsWith("/")) path = path.substring(1)
                it.name to path
            }.toMap()

            val newTxt = txtLines.map {
                val i = it.indexOf(iml)
                val moduleName = it.substring(0, i.coerceAtLeast(0)).substringAfterLast("/")
                val newGroup = if (moduleName == target.name) defGroup else groups[moduleName] ?: ""
                it.replace(rex, "group=\"$newGroup\"")
            }

            val txt = newTxt.joinToString("\n") + "\n<!--${Date().toLocaleString()}--> $tag"
            val w = moduleXml.getOutputStream(object : SafeWriteRequestor {}).writer()
            w.write(txt)
            w.close()
            moduleXml.refresh(false, true)
            target.save()
        })
        return true
    }

    fun invokeLaterOnWriteThread(action: Runnable) = ApplicationManager.getApplication().invokeLater {
        ApplicationManager.getApplication().runWriteAction(action)
    }

    fun getSelectDevice(project: Project): IDevice?  = DeviceGet.getDevice(project)

    fun setTransfer(component: JComponent, block: (files: List<File>) -> Unit) {
        component.transferHandler = object : TransferHandler() {
            override fun importData(p0: JComponent?, t: Transferable): Boolean {
                try {
                    val o = t.getTransferData(DataFlavor.javaFileListFlavor)

                    block(o as List<File>)
                    return true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                return false
            }

            override fun canImport(p0: JComponent?, flavors: Array<out DataFlavor>?): Boolean {
                return flavors?.find { DataFlavor.javaFileListFlavor.equals(it) } != null
            }
        }
    }

    fun installApk(device: IDevice, path: String, params: String): String = try {
        val newPath = "/data/local/tmp/${path.hashCode()}.apk"
        device.pushFile(path, newPath)
        val r = AdbShellCommandsUtil.executeCommand(device, "pm  install $params  $newPath").output.findLast { it.trim().isNotEmpty() }
                ?: "Unknow Exception"
        if (r.contains("Success")) adbShellCommon(device, "rm -f $newPath", false)
        else adbShellCommon(device, "mv $newPath /sdcard/${path.substringAfterLast("/")}", false)
        r
    } catch (e: Exception) {
        e.toString()
    }

    fun adbShellCommon(device: IDevice, cmd: String, firstLine: Boolean): String = try {
        val output = AdbShellCommandsUtil.executeCommand(device, cmd).output
        if (firstLine || output.size == 1) output[0] else output.toString()
    } catch (e: Exception) {
        e.printStackTrace()
        ""
    }

    fun getAppInfoFromApk(fileApk: File): AndroidApplicationInfo? = try {
        val invoker = AaptInvoker(AndroidSdks.getInstance().tryToChooseSdkHandler(), LogWrap())
        val xmlTree = invoker.getXmlTree(fileApk, "AndroidManifest.xml")
        AndroidApplicationInfo.parse(xmlTree)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
    fun tryInstall(project: Project, d: IDevice?, path: String, param: String) {
        val device = d ?: DeviceGet.getDevice(project)
        ?: return ApplicationManager.getApplication().invokeLater { Messages.showMessageDialog("No Device Connect", "Miss Device", null) }

        val task = object : Task.Backgroundable(project, "Start Install") {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Install : $path"
                val install = installApk(device, path, param).toLowerCase(Locale.CHINESE)
                if (install.contains("success")) {
                    val packageId = UiUtils.getAppInfoFromApk(File(path))?.packageId
                    if (packageId != null) {
                        indicator.text = "Open : $packageId"
                        val lauchActivity = AdbShellCommandsUtil.executeCommand(device, "dumpsys package $packageId").output.find { it.contains(packageId) }
                        //打开应用
                        if (lauchActivity?.isNotEmpty() == true) {
                            AdbShellCommandsUtil.executeCommand(device, "am start -n ${lauchActivity.substring(lauchActivity.indexOf(packageId)).split(" ").first().trim()}")
                        }
                    }
                } else ApplicationManager.getApplication().invokeLater {
                    val n = Notification(Notifications.SYSTEM_MESSAGES_GROUP_ID, "Install Fail", install, NotificationType.WARNING)
                    n.addAction(object : NotificationAction("ReTry") {
                        override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                            tryInstall(project, device, path, param)
                            n.expire()
                        }
                    })
                    n.notify(project)
                }
            }
        }
        ProgressManager.getInstance().runProcessWithProgressAsynchronously(task, BackgroundableProcessIndicator(task))
    }

    fun base64Encode(source: String) = String(Base64.getEncoder().encode(source.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
    fun base64Decode(source: String) = String(Base64.getDecoder().decode(source.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
}