package com.pqixing.modularization.utils

import com.pqixing.modularization.Keys
import com.pqixing.modularization.base.BasePlugin
import com.pqixing.modularization.common.GlobalConfig
import com.pqixing.modularization.net.Net
import com.pqixing.modularization.wrapper.MetadataWrapper
import com.pqixing.modularization.wrapper.PomWrapper
import com.pqixing.modularization.wrapper.ProjectWrapper

class MavenUtils {

    static Properties getMavenMaps(String mavenName) {
        String mapKey = "${mavenName}Maps"
        if (BasePlugin.rootProject.hasProperty(mapKey)) {
            return BasePlugin.rootProject."$mapKey"
        }
        String docGitName = GitUtils.getNameFromUrl(GlobalConfig.docGitUrl)
        def mapFile = new File(BasePlugin.rootProject.rootDir.parentFile, "$docGitName/$Keys.MODURIZATION/$Keys.MAVEN/$mavenName/$Keys.FILE_VERSION")
        BasePlugin.rootProject.ext."$mapKey" = FileUtils.readMaps(mapFile)

        FileUtils.createIfNotExist(mapFile)
        return BasePlugin.rootProject."$mapKey"
    }

    static File checkDocDir(ProjectWrapper wrapper) {
        String docGitName = GitUtils.getNameFromUrl(GlobalConfig.docGitUrl)
        File docDir = GitUtils.findGitDir(new File(wrapper.project.rootDir.parentFile, docGitName))
        if (CheckUtils.isEmpty(docDir)) {//如果文档库还不存在
            GitUtils.run("git clone $GlobalConfig.docGitUrl", wrapper.project.rootDir)
        } else GitUtils.run("git pull", docDir)

        return docDir
    }

    /**
     * 更新某个模块的maven仓库记录
     */
    static boolean updateMavenRecord(ProjectWrapper wrapper, String mavenName, String mavenUrl, String artifactId) {
        MetadataWrapper metaWrapper = MetadataWrapper.create(mavenUrl, GlobalConfig.groupName, artifactId)
        if (metaWrapper.empty) return false

        File docDir = checkDocDir(wrapper)
        if (!docDir.exists()) return false

        File mavenDir = new File(docDir, "$Keys.MODURIZATION/$Keys.MAVEN/$mavenName")

        //保存最新的版本号
        File versionMaps = new File(mavenDir, Keys.FILE_VERSION)
        def maps = FileUtils.readMaps(versionMaps)
        maps.put(artifactId, metaWrapper.release)
        FileUtils.saveMaps(maps, versionMaps)

        //缓存meta 文件
        FileUtils.write(new File(mavenDir, "$artifactId/meta.xml"), metaWrapper.xmlString)
        def versions = metaWrapper.versions
        Print.ln("updateMavenRecord $mavenName :- >$versions")
        //缓存所有版本的pom 文件
        versions.each { v ->
            File pomFile = new File(mavenDir, "$artifactId/$v/pom.xml")
            if (!pomFile.exists()) {
                String pomXml = Net.get(PomWrapper.getPomUrl(mavenUrl, GlobalConfig.groupName, artifactId, v), true)
                if (!CheckUtils.isEmpty(pomXml)) FileUtils.write(pomFile, pomXml)
            }
        }
//        GitUtils.run("git add ${$Keys.MODURIZATION}/*&& git commit -m 'update version $artifactId '&& git push", docDir)
    }
}