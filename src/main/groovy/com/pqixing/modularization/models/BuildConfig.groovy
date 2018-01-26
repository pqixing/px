package com.pqixing.modularization.models

import com.pqixing.modularization.Default
import com.pqixing.modularization.utils.FileUtils
import org.gradle.api.Project

/**
 * Created by pqixing on 17-12-7.
 */

class BuildConfig extends BaseExtension {
    String cacheDir
    String outDir
    String javaDir
    String groupName = "com.dachen"
    final String projectName
    String packageName
    String defRepoPath
    String rootPath

    BuildConfig(Project project) {
        projectName = project.name.replace("_","").replace("-","")//去掉下划线
        rootPath = project.projectDir.path.replace("\\","/")
        outDir = FileUtils.appendUrls(rootPath, ".modularization")
        cacheDir = FileUtils.appendUrls(outDir, ".cache")
        javaDir = FileUtils.appendUrls(outDir, "java")

        defRepoPath = FileUtils.appendUrls(project.rootDir.absolutePath, ".modularization", "module.version")
        File ignoreFile = new File(rootPath,".gitignore")
        if (!ignoreFile.exists()) ignoreFile.createNewFile()
        if (!ignoreFile.text.contains(".modularization")) ignoreFile.append("\n.modularization \n")

        groupName = Default.groupName
        packageName = groupName + '.' + projectName
        packageName = packageName.replace("-","").replace("_","").toLowerCase()
        project.ext.buildConfig = this

//        updateMeta(project)
    }

    @Override
    LinkedList<String> generatorFiles() {
        return ""
    }

    @Override
    public String toString() {
        return "BuildConfig{" +
                "cacheDir='" + cacheDir + '\'' +
                ", outDir='" + outDir + '\'' +
                ", groupName='" + groupName + '\'' +
                ", projectName='" + projectName + '\'' +
                ", packageName='" + packageName + '\'' +
                '}';
    }
}
