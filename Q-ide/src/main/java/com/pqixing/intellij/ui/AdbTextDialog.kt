package com.pqixing.intellij.ui

import android.util.Base64
import com.android.ddmlib.IDevice
import com.android.tools.idea.explorer.adbimpl.AdbShellCommandsUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.impl.BackgroundableProcessIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.pqixing.intellij.utils.DachenHelper
import com.pqixing.intellij.utils.UiUtils
import java.awt.Desktop
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.net.URI
import javax.swing.*

class AdbTextDialog(var project: Project) : BaseJDialog() {
    private val adbInputPkg = "com.pqixing.adbkeyboard"
    private var contentPane: JPanel? = null
    private var toClipButton: JButton? = null
    private var toEditButton: JButton? = null
    public var jText: JTextArea? = null
    private var cbDevices: JComboBox<String>? = null
    private var fromEditButton: JButton? = null
    private var fromClipButton: JButton? = null
    private var clipVersion = "1.1"
    val resultKey = "onIdeResult="

    init {
        setContentPane(contentPane)
        isModal = false
        title = "Adb Text Editor"
        toClipButton!!.addActionListener { e -> toPhone(false, toClipButton) }
        toEditButton!!.addActionListener { e -> toPhone(true, toEditButton) }
        fromEditButton!!.addActionListener { e -> fromPhone(true, fromEditButton) }
        fromClipButton!!.addActionListener { e -> fromPhone(false, fromClipButton) }

        // call onCancel() when cross is clicked
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                dispose()
            }
        })

        // call onCancel() on ESCAPE
        contentPane!!.registerKeyboardAction({ e -> dispose() }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        UiUtils.initDevicesComboBox(project, cbDevices!!)
        UiUtils.setTransfer(jText!!) {
            jText!!.text = it.joinToString("\n")
        }
    }

    /**
     * 执行命令并且返回结果
     */
    private fun runAdCommand(device: IDevice, cmd: String): String? {
        val result = AdbShellCommandsUtil.executeCommand(device, cmd).output.toString()
        if (!result.contains(resultKey)) return null

        val start = result.indexOf(resultKey) + resultKey.length
        val end = result.indexOf("\"", start)
        return String(Base64.decode(result.substring(start, if (end == -1) result.length else end).trim(), 0))
    }


    private fun getBroadCastCmd(action: String, value: String? = null): String {
        var cmd = "am broadcast -a $adbInputPkg.$action "
        if (value?.isNotEmpty() == true) cmd = "$cmd -e AdbReceiver \"${String(Base64.encode(value.toByteArray(), 0))}\""
        return cmd
    }

    private fun checkHelper(iDevice: IDevice): Boolean {
        var result = runAdCommand(iDevice, getBroadCastCmd("get_version"))
        if (result == null) {
            val hadInstall = AdbShellCommandsUtil.executeCommand(iDevice, "pm ").output?.find { it == adbInputPkg } != null
            if (hadInstall) ApplicationManager.getApplication().invokeLater {
                Messages.showMessageDialog("ADB Keyboard 不是当前指定的输入发, 请切换后重新输入?", "ADB Keyboard", null)
            } else ApplicationManager.getApplication().invokeLater {
                val exit = Messages.showOkCancelDialog("下载安装ADB Keyboard?", "ADB Keyboard未安装", null)
                if (exit == Messages.OK) installClipHelper(iDevice)
            }
            return false
        }
        if (result < clipVersion) {
            ApplicationManager.getApplication().invokeLater {
                val exit = Messages.showOkCancelDialog("下载升级ADB Keyboard?", "ADB Keyboard版本过低", null)
                if (exit == Messages.OK) installClipHelper(iDevice)
            }
            return false
        }
        return true
    }

    private fun toPhone(edit: Boolean, btn: JButton?) = ProgressManager.getInstance().runProcess({
        val iDevice = UiUtils.getSelectDevice(project, cbDevices!!) ?: return@runProcess
        btn?.isEnabled = false
        if (!checkHelper(iDevice)) {
            btn?.isEnabled = true
            return@runProcess
        }

        val adCommand = runAdCommand(iDevice, getBroadCastCmd(if (edit) "set_text_edit" else "set_text", jText!!.text))
        if ("##fail##" == adCommand) ApplicationManager.getApplication().invokeLater {
            Messages.showMessageDialog("无法设置文本,请检查Adb Keyboard输入法是否正常", "设置文本失败", null)
        }
        btn?.isEnabled = true
    }, null)

    private fun fromPhone(edit: Boolean, btn: JButton?) = ProgressManager.getInstance().runProcess({
        val iDevice = UiUtils.getSelectDevice(project, cbDevices!!) ?: return@runProcess
        btn?.isEnabled = false
        if (!checkHelper(iDevice)) {
            btn?.isEnabled = true
            return@runProcess
        }

        var adCommand = runAdCommand(iDevice, getBroadCastCmd(if (edit) "get_text_edit" else "get_text"))
        if ("##fail##" == adCommand || adCommand == null) ApplicationManager.getApplication().invokeLater {
            Messages.showMessageDialog("无法设置文本,请检查Adb Keyboard输入法是否正常", "设置文本失败", null)
        } else jText?.text = adCommand
        btn?.isEnabled = true
    }, null)

    private fun installClipHelper(iDevice: IDevice) {
        val install = object : Task.Backgroundable(project, "Start Install") {

            override fun run(indicator: ProgressIndicator) {
                val url = "https://raw.githubusercontent.com/pqixing/modularization/master/jars/Q-keyboard-debug.apk"
                indicator.text = "Download : $url"
                try {
                    val downloadApk = DachenHelper.downloadApk(project, "copy", url)
                    if (downloadApk.isEmpty() || !File(downloadApk).exists()) ApplicationManager.getApplication().invokeLater {
                        if (Messages.OK == Messages.showOkCancelDialog("请尝试使用浏览器进行下载并手动安装?", "下载失败", null)) {
                            Desktop.getDesktop().browse(URI(url))
                        }
                    } else {
                        indicator.text = "Install : $url"
                        UiUtils.installApk(iDevice, downloadApk, "-r -t")
                        UiUtils.adbShellCommon(iDevice, "am start -n com.pqixing.clieper/com.pqixing.clieper.MainActivity", false)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        ProgressManager.getInstance().runProcessWithProgressAsynchronously(install, BackgroundableProcessIndicator(install))

    }

}
