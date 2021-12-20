package tests.bsp

import scala.meta.internal.bsp.BspExtra
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.internal.semver.SemVer

import munit.FunSuite

class BspExtraSuite extends FunSuite {

  test("new releases") {
    def decresePatchVersion(v: String): String = {
      val parsed = SemVer.Version.fromString(v)
      parsed.copy(patch = parsed.patch - 1).toString
    }
    val known =
      BspExtra(
        decresePatchVersion(V.scalametaVersion),
        List(V.scala212, V.scala213).map(decresePatchVersion)
      )

    val result = BspExtra.discoverNewReleases(known)
    assert(result.semanticdbVersion != known)
    assert(result.supportedScalaVersions.contains(V.scala212))
    assert(result.supportedScalaVersions.contains(V.scala213))
  }
}
