package com.pqixing.git

import com.pqixing.Tools
import com.pqixing.interfaces.ICredential
import com.pqixing.interfaces.ILog
import com.pqixing.tools.CheckUtils.isGitDir
import com.pqixing.tools.FileUtils
import org.eclipse.jgit.api.*
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

object GitUtils {
    lateinit var credentials: ICredential
    fun init(credentials: ICredential) {
        this.credentials = credentials
    }

    /**
     * clone
     * @param gitUrl
     * @param dirPath
     * @return
     */
    fun clone(gitUrl: String, gitDir: File, branchName: String = "master"): Git? {
        val git = Git.cloneRepository()
                .setURI(gitUrl).setDirectory(gitDir).setBranch(branchName)
                .init().execute()
        if (git == null) {
            if (branchName != "master") return clone(gitUrl, gitDir, "master")
        } else {
            //如果名字不等（clone的分支不存在） checkout到master
            if (git.repository.branch != branchName) {
                git.checkout().setName("master")
                        .setCreateBranch(true)
                        .setStartPoint("refs/remotes/origin/master")
                        .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM)
                        .init().execute()
            }
        }
        return git
    }

    /**
     * 查找出对应的git根目录
     * @param dir
     * @return
     */
    fun findGitDir(dir: File?): File? {
        var p = dir
        var g: File?
        while (p != null) {
            if (isGitDir(p)) return p
            p = p.parentFile
        }
        return null
    }

    inline fun isGitDir(dir: File): Boolean {
        val g = File(dir, ".git")
        return g.isDirectory && g.exists()
    }

    @JvmStatic
    fun getGitNameFromUrl(url: String?): String {
        url ?: return ""
        val s = url.lastIndexOf("/") + 1
        val e = url.indexOf(".", s)
        return url.substring(s, if (e == -1) url.length else e)
    }

}

fun <T> GitCommand<T>.init(provider: UsernamePasswordCredentialsProvider? = null): GitCommand<T> {
    if (this is TransportCommand<*, *>) {
        if (provider != null) setCredentialsProvider(provider)
        else setCredentialsProvider(UsernamePasswordCredentialsProvider(GitUtils.credentials.getUserName(), GitUtils.credentials.getPassWord()))
    }
    if (this is PullCommand) this.setProgressMonitor(PercentProgress())
    if (this is PushCommand) this.progressMonitor = PercentProgress()
    if (this is CloneCommand) this.setProgressMonitor(PercentProgress())
    if (this is CheckoutCommand) this.setProgressMonitor(PercentProgress())
    if (this is CheckoutCommand) this.setProgressMonitor(PercentProgress())
    return this
}

fun <T> GitCommand<T>.execute(): T? = try {
    Tools.println("Git task ->  ${javaClass.simpleName}")
    val call = call()
    val repo: Repository = (call as? Git)?.repository ?: repository
    Tools.println("Git task end -> ${javaClass.simpleName} : ${repo?.branch} : $repo \n $call")
    call
} catch (e: Exception) {
    ///home/pqixing/Desktop/gradleProject/Root/Document/.git
//    FileUtils.delete(File(repository.directory, "index.lock"))
    Tools.println(e.toString())
    null
}


