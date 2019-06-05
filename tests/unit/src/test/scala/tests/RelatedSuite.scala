package tests

import scala.meta.internal.builds.GradleBuildTool
import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.io.PathIO
import scala.meta.io.AbsolutePath
import scala.meta.internal.builds.MavenBuildTool

object RelatedSuite extends BaseSuite {
  private def addNot(flag: Boolean) =
    if (flag) {
      ""
    } else {
      "Not"
    }
  def checkRelated(
      relpath: String,
      isRelated: (AbsolutePath, AbsolutePath) => Boolean,
      isTrue: Boolean = true
  ): Unit = {
    val workspace = PathIO.workingDirectory
    val path = workspace.resolve(relpath)
    if (isTrue) assert(isRelated(workspace, path))
    else assert(!isRelated(workspace, path))
  }

  /**------------ SBT ------------**/
  def checkIsNotSbtRelated(relpath: String): Unit = {
    checkIsSbtRelated(relpath, isTrue = false)
  }
  def checkIsSbtRelated(relpath: String, isTrue: Boolean = true): Unit = {
    test(s"is${addNot(isTrue)}SbtRelated - $relpath") {
      checkRelated(relpath, SbtBuildTool.isSbtRelatedPath, isTrue)
    }
  }

  checkIsSbtRelated("project/build.properties")
  checkIsSbtRelated("project/Dependencies.scala")
  checkIsSbtRelated("project/Build.scala")
  checkIsSbtRelated("project/project/plugins.sbt")
  checkIsSbtRelated("project/project/build.sbt")
  checkIsSbtRelated("project/build.sbt")
  checkIsSbtRelated("build.sbt")
  checkIsNotSbtRelated("src/main/scala/Main.scala")

  /**------------ Gradle ------------**/
  def checkIsNotGradleRelated(relpath: String): Unit = {
    checkIsGradleRelated(relpath, isTrue = false)
  }
  def checkIsGradleRelated(relpath: String, isTrue: Boolean = true): Unit = {
    test(s"is${addNot(isTrue)}GradleRelated - $relpath") {
      checkRelated(relpath, GradleBuildTool.isGradleRelatedPath, isTrue)
    }
  }
  checkIsGradleRelated("build.gradle")
  checkIsGradleRelated("build.gradle.kts")
  checkIsGradleRelated("a/b/c/build.gradle")
  checkIsGradleRelated("a/b/c/build.gradle.kts")
  checkIsGradleRelated("buildSrc/Hello.kts")
  checkIsGradleRelated("buildSrc/a/b/c/Hello.java")
  checkIsGradleRelated("buildSrc/a/b/c/Hello.groovy")
  checkIsNotGradleRelated("/ab/c/A.groovy")
  checkIsNotGradleRelated("/ab/c/A.kts")

  /**------------ Maven ------------**/
  def checkIsNotMavenRelated(relpath: String): Unit = {
    checkIsMavenRelated(relpath, isTrue = false)
  }
  def checkIsMavenRelated(relpath: String, isTrue: Boolean = true): Unit = {
    test(s"is${addNot(isTrue)}MavenRelated - $relpath") {
      checkRelated(relpath, MavenBuildTool.isMavenRelatedPath, isTrue)
    }
  }
  checkIsMavenRelated("pom.xml")
  checkIsMavenRelated("a/b/c/pom.xml")
  checkIsNotMavenRelated("a/b/c/settings.xml")
  checkIsNotMavenRelated("other.xml")
}
