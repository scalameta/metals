package tests

import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.SbtChecksums
import scala.meta.internal.metals.SbtChecksum.Status._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.SbtChecksum

object SbtChecksumsSuite extends BaseTablesSuite {
  def sbtChecksums: SbtChecksums = tables.sbtChecksums
  test("basic") {
    assertEquals(sbtChecksums.setStatus("a", Requested), 1)
    assertEquals(sbtChecksums.last().get, SbtChecksum("a", Requested))
    time.elapseSeconds(1)
    assertEquals(sbtChecksums.getStatus("a").get, Requested)
    assertEquals(sbtChecksums.setStatus("a", Installed), 1)
    assertEquals(sbtChecksums.last().get, SbtChecksum("a", Installed))
    time.elapseSeconds(1)
    assertEquals(sbtChecksums.getStatus("a").get, Installed)
  }
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
