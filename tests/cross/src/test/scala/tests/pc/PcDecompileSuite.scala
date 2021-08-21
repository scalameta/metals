package tests.pc

import java.net.URI

import java.util.concurrent.TimeUnit

import munit.Location
import tests.BasePCSuite
import tests.BuildInfoVersions
import java.nio.file.Paths

class PcDecompileSuite extends BasePCSuite {

  override protected def excludedScalaVersions: Set[String] =
    BuildInfoVersions.scala2Versions.toSet

  check(
    "simple"
  )

  def check(
      name: String,
      filename: String = "A.scala"
  )(implicit loc: Location): Unit = {
    test(name) {
      val uri = new URI(s"file:///$filename")
      pprint.log(presentationCompiler)
      val doc = presentationCompiler
        .decompile(uri, Paths.get("./a.scala"))
        .get(2, TimeUnit.SECONDS)
        .get()
        .getArguments()
        .get(0)
        .asInstanceOf[String]

      assertNoDiff(
        doc,
        s""
      )
    }
  }
}
