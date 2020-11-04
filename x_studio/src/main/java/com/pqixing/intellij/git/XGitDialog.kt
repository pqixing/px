package com.pqixing.intellij.git

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.pqixing.EnvKeys
import com.pqixing.help.XmlHelper
import com.pqixing.intellij.XApp
import com.pqixing.intellij.actions.XAnAction
import com.pqixing.intellij.git.uitils.GitHelper
import com.pqixing.intellij.ui.form.XDialog
import com.pqixing.intellij.ui.form.XItem
import git4idea.GitUtil
import git4idea.commands.GitLineHandlerListener
import git4idea.repo.GitRepository
import java.awt.MenuItem
import java.awt.event.ActionListener
import java.io.File
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class XGitAction : XAnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        XGitDialog(e.project ?: return, e).show()
    }
}

class XGitDialog(val project: Project, val e: AnActionEvent) : XDialog(project) {
    private val KEY_FILE = "file"
    private val KEY_URL = "url"
    private val KEY_REPO = "repo"
    private val KEY_DESC = "desc"
    private lateinit var pTop: JPanel
    private lateinit var cbBrn: JComboBox<String>
    private lateinit var cbOp: JComboBox<IGitRun>
    private lateinit var jlPath: JLabel
    val basePath = e.project?.basePath!!
    val codeRoot = File(basePath, XmlHelper.loadConfig(basePath).codeRoot).canonicalPath
    val manifest = XmlHelper.loadManifest(basePath)!!
    val allBrns = mutableSetOf<String>()
    val listener = GitLineHandlerListener { l, k -> XApp.log(l) }

    init {
        title = "Git"
        jlPath.text = "  : $codeRoot"
        myAll = ActionListener {
            val isSelected = myCheckBoxDoNotShowDialog.isSelected
            adapter.datas().filter { it.visible }.forEach { it.select = isSelected }
        }
        XApp.post { loadItems() }
    }

    private fun loadItems() {
        val newItem = { name: String, tag: String, url: String, file: File ->
            XItem().also { item ->
                item.title = name
                item.tag = tag
                item.select = name != EnvKeys.BASIC
                item.selectAble = true
                item.params[KEY_FILE] = file
                item.params[KEY_URL] = url
                item.params[KEY_DESC] = tag
                item.content = url
            }
        }
        val items = manifest.projects.map { p -> newItem(p.name, p.desc, p.url, File(codeRoot, p.path)) }.toMutableList()
        items.add(0, newItem(EnvKeys.BASIC, "base library", "this is secret ~~", File(basePath, EnvKeys.BASIC)))

        arrayOf(Check(), Merge(), Clone(), Pull(), Push(), Create(), Delete()).forEach { cbOp.addItem(it) }
        cbOp.addActionListener { fetchRepo() }
        adapter.set(items)
        XApp.invoke { fetchRepo() }
    }

    override fun doOKAction() = XApp.runAsyn { indicator ->
        btnEnable(false)

        val gitOp = cbOp.selectedItem as IGitRun
        val selects = adapter.datas().filter { it.visible && it.select }
        if (gitOp.beforeRun(selects)) selects.onEach {
            it.tag = it.getState(null)
            indicator.text = "${gitOp.javaClass.simpleName} -> ${it.title} : ${it.content}"
            gitOp.run(it)
        }
        indicator.text = "${gitOp.javaClass.simpleName} -> run after"
        gitOp.afterRun(selects)

        btnEnable(true)
    }

    override fun btnEnable(enable: Boolean) {
        super.btnEnable(enable)
        cbOp.isEnabled = enable
        cbBrn.isEnabled = enable
    }


    fun fetchRepo() {
        val gitOp = cbOp.selectedItem as IGitRun
        for (item in adapter.datas()) {
            item.tag = item.get<String>(KEY_DESC).toString()
            if (item.get<GitRepository>(KEY_REPO) != null) {
                item.visible = gitOp.visible(item)
                continue
            }
            val repo = item.get<File>(KEY_FILE)?.toRepo()
            item.params[KEY_REPO] = repo
            item.visible = gitOp.visible(item)
            if (repo == null) continue

            item.content = repo.currentBranchName ?: "Not Found"
            val brs = repo.branches
            val news = brs.localBranches.map { it.name }.plus(brs.remoteBranches.map { it.name.substringAfter("/") })
            news.filter { allBrns.add(it) }.forEach { cbBrn.addItem(it) }
        }
    }


    override fun createNorthPanel(): JComponent? = pTop

    fun File?.toRepo() = this?.takeIf { GitUtil.isGitRoot(it) }?.let { GitHelper.getRepo(it, project) }


    /**  start run **/
    abstract inner class IGitRun {
        open fun visible(item: XItem): Boolean = item.get<GitRepository>(KEY_REPO) != null
        open fun run(item: XItem) {}
        open fun afterRun(items: List<XItem>) {}
        open fun beforeRun(items: List<XItem>): Boolean = true
        override fun toString(): String = this.javaClass.simpleName
    }

    private inner class None : IGitRun() {
        override fun visible(item: XItem) = true
    }

    private inner class Clone : IGitRun() {
        override fun visible(item: XItem): Boolean = item.get<String>(KEY_URL) != null && !super.visible(item)
        override fun run(item: XItem) {
            item.tag = item.getState(GitHelper.clone(project, item.get(KEY_FILE)!!, item.get(KEY_URL)!!, listener))
        }
    }

    private inner class Push : IGitRun() {
        override fun run(item: XItem) {
            val repo = item.get<GitRepository>(KEY_REPO)
            item.tag = GitHelper.push(project, repo, listener).let { item.getState(it == "Success", it) }
        }
    }

    private inner class Pull : IGitRun() {
        override fun run(item: XItem) {
            val repo = item.get<GitRepository>(KEY_REPO)
            item.tag = GitHelper.update(project, repo, listener).let { item.getState(it == "Success", it) }
        }
    }

    private inner class Merge : IGitRun() {
        override fun beforeRun(items: List<XItem>): Boolean {
            var enable = false
            XApp.invoke(true) {
                enable = Messages.OK == Messages.showOkCancelDialog(project, "Make Sure Merge ${cbBrn.selectedItem?.toString()}", "Warning", "Delete", "Cancel", null)
            }
            return enable
        }

        override fun run(item: XItem) {
            val branch = cbBrn.selectedItem?.toString()
            val repo = item.get<GitRepository>(KEY_REPO)
            item.tag = (if (!GitHelper.checkBranchExists(repo, branch)) "None" else GitHelper.merge(project, branch, repo, listener)).let { item.getState(it == "Success", it) }
        }
    }

    private inner class Delete : IGitRun() {
        override fun beforeRun(items: List<XItem>): Boolean {
            var enable = false
            XApp.invoke(true) {
                enable = Messages.OK == Messages.showOkCancelDialog(project, "Make Sure Delete ${cbBrn.selectedItem?.toString()}", "Warning", "Delete", "Cancel", null)
            }
            return enable
        }

        override fun run(item: XItem) {

            val branch = cbBrn.selectedItem?.toString() ?: return
            val repo = item.get<GitRepository>(KEY_REPO)
            item.tag = GitHelper.delete(project, branch, repo, listener).let { item.getState(it == "Success", it) }
        }
    }

    private inner class Create : IGitRun() {
        override fun run(item: XItem) {
            val branch = cbBrn.selectedItem?.toString() ?: return
            val repo = item.get<GitRepository>(KEY_REPO)
            item.tag = GitHelper.create(project, branch, repo, listener).let { item.getState(it == "Success", it) }

        }
    }

    private inner class Check : IGitRun() {
        override fun run(item: XItem) {
            item.tag = item.getState(null)
        }

        override fun afterRun(items: List<XItem>) {
            val branch = cbBrn.selectedItem?.toString()
            val repos = items.mapNotNull { it.get<GitRepository>(KEY_REPO) }

            var wait = true
            GitHelper.checkout(project, branch, repos) { wait = false }

            while (wait) {
            }

            repos.forEach { it.update() }

            items.forEach { item ->
                val newBranch = item.get<GitRepository>(KEY_REPO)?.currentBranchName
                if (newBranch != null) {
                    item.content = newBranch
                }
                item.tag = item.getState(newBranch == branch)
            }
        }

    }

    private fun XItem.getState(success: Boolean?, newTag: String? = null): String = XItem.state(success) + (newTag
            ?: this.get<String>(KEY_DESC).toString())
}

