package tests

import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.MetalsEnrichments._

object SbtRelatedSuite extends BaseSuite {
  def checkIsNotSbtRelated(relpath: String): Unit = {
    checkIsSbtRelated(relpath, isTrue = false)
  }
  def checkIsSbtRelated(relpath: String, isTrue: Boolean = true): Unit = {
    test(s"isSbtRelated - $relpath") {
      val workspace = PathIO.workingDirectory
      val path = workspace.resolve(relpath)
      if (isTrue) assert(path.isSbtRelated(workspace))
      else assert(!path.isSbtRelated(workspace))
    }
  }
  checkIsSbtRelated("project/build.properties")
  checkIsSbtRelated("project/Dependencies.scala")
  checkIsSbtRelated("project/Build.scala")
  checkIsSbtRelated("project/build.sbt")
  checkIsSbtRelated("build.sbt")
  checkIsNotSbtRelated("src/main/scala/Main.scala")
}
