package tests

import scala.meta.internal.builds.GradleBuildTool
import scala.meta.internal.builds.MavenBuildTool
import scala.meta.internal.builds.MillBuildTool
import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.io.PathIO
import scala.meta.io.AbsolutePath

import munit.Location

class RelatedSuite extends BaseSuite {
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
  )(implicit loc: Location): Unit = {
    val workspace = PathIO.workingDirectory
    val path = workspace.resolve(relpath)
    if (isTrue) assert(isRelated(workspace, path))
    else assert(!isRelated(workspace, path))
  }

  /**
   * ------------ SBT ------------* */
  def checkIsNotSbtRelated(relpath: String)(implicit loc: Location): Unit = {
    checkIsSbtRelated(relpath, isTrue = false)
  }
  def checkIsSbtRelated(relpath: String, isTrue: Boolean = true)(implicit
      loc: Location
  ): Unit = {
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

  /**
   * ------------ Gradle ------------* */
  def checkIsNotGradleRelated(relpath: String)(implicit loc: Location): Unit = {
    checkIsGradleRelated(relpath, isTrue = false)
  }
  def checkIsGradleRelated(relpath: String, isTrue: Boolean = true)(implicit
      loc: Location
  ): Unit = {
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

  /**
   * ------------ Maven ------------* */
  def checkIsNotMavenRelated(relpath: String)(implicit loc: Location): Unit = {
    checkIsMavenRelated(relpath, isTrue = false)
  }
  def checkIsMavenRelated(relpath: String, isTrue: Boolean = true)(implicit
      loc: Location
  ): Unit = {
    test(s"is${addNot(isTrue)}MavenRelated - $relpath") {
      checkRelated(relpath, MavenBuildTool.isMavenRelatedPath, isTrue)
    }
  }
  checkIsMavenRelated("pom.xml")
  checkIsMavenRelated("a/b/c/pom.xml")
  checkIsNotMavenRelated("a/b/c/settings.xml")
  checkIsNotMavenRelated("other.xml")

  /**
   * ------------ Mill ------------* */
  def checkIsNotMillRelated(relpath: String)(implicit loc: Location): Unit = {
    checkIsMillRelated(relpath, isTrue = false)
  }
  def checkIsMillRelated(relpath: String, isTrue: Boolean = true)(implicit
      loc: Location
  ): Unit = {
    test(s"is${addNot(isTrue)}MillRelated - $relpath") {
      checkRelated(relpath, MillBuildTool.isMillRelatedPath, isTrue)
    }
  }
  checkIsMillRelated("build.sc")
  checkIsMillRelated("a/b/c/other.sc")
  checkIsNotMillRelated("/ab/c/A.groovy")
  checkIsNotMillRelated("/ab/c/A.kts")
  checkIsNotMillRelated("/ab/c/A.scala")
}
