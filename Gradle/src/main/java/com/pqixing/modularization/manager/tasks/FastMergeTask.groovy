package com.pqixing.modularization.manager.tasks

import com.pqixing.modularization.Keys

/**
 * Created by pqixing on 17-12-20.
 * 同步文档的任务
 */

class FastMergeTask extends GitTask {

    @Override
    String onGitProject(String gitName, String gitUrl, File gitDir) {
        if (!gitDir.exists()) return Keys.TIP_GIT_NOT_EXISTS
        boolean hasRemote = false
        boolean hasLocal = false
        boolean isCurBranch = false
        com.pqixing.modularization.gradle.utils.GitUtils.run("manager branch -a ", gitDir, false)?.eachLine { line ->
            line = line.trim()
            if (line.contains("/origin/$branchName")) {
                hasRemote = true
            }
            if (!line.contains("/origin/") && line.endsWith(branchName)) {
                hasLocal = true
                isCurBranch = line != branchName
            }
        }
        if (isCurBranch) return "current branch is $branchName"
        if (!hasRemote) return Keys.TIP_BRANCH_NOT_EXISTS
        com.pqixing.modularization.gradle.utils.GitUtils.run("manager pull", gitDir)
        def mergeResult = com.pqixing.modularization.gradle.utils.GitUtils.run("manager merge origin/$branchName  --ff-only", gitDir)?.trim() ?: ""
        if (mergeResult.startsWith("fatal:")) return Keys.TIP_GIT_MERGE_FAIL
        return com.pqixing.modularization.gradle.utils.GitUtils.run("manager push", gitDir)

    }
}