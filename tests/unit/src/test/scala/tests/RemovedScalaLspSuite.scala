package tests

import scala.annotation.tailrec
import scala.util.Try

import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MtagsResolver
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.internal.semver.SemVer

import coursierapi.Dependency
import coursierapi.Fetch
import munit.IgnoreSuite

@IgnoreSuite
class RemovedScalaLspSuite extends BaseLspSuite("cascade") {

  override protected def mtagsResolver: MtagsResolver = MtagsResolver.default()

  test("versions-should-be-added") {
    check(firstSupported = "2.12.9", lastSupported = V.scala212)
    check(firstSupported = "2.13.1", lastSupported = V.scala213)
    check(firstSupported = "3.0.0", lastSupported = V.scala3)
  }

  test("check-support") {
    cleanWorkspace()
    val withDocumentHighlight =
      """|package a
         |object A {
         |  val <<ag@@e>> = 42
         |  <<age>> + 12
         |}""".stripMargin
    val fileContents =
      withDocumentHighlight
        .replace(">>", "")
        .replace("<<", "")
        .replace("@@", "")
    val expected = withDocumentHighlight.replace("@@", "")
    val edit = withDocumentHighlight.replace(">>", "").replace("<<", "")
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": { "scalaVersion" : "2.13.1" }
           |}
           |/a/src/main/scala/a/A.scala
           |$fileContents
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        Messages.DeprecatedRemovedScalaVersion.message(Set("2.13.1")),
      )
      // document highlight is available in 0.11.10 for 3.0.0
      _ <- server.assertHighlight(
        "a/src/main/scala/a/A.scala",
        edit,
        expected,
      )
      // semantic highlight was not available in 0.11.10
      _ <- server.didChangeConfiguration(
        """{
          |  "enable-semantic-highlighting": true
          |}
          |""".stripMargin
      )
      _ <- server.assertSemanticHighlight(
        "a/src/main/scala/a/A.scala",
        // we get only semantic tokens for keywords, tokens for symbols are missing
        """|<<package>>/*keyword*/ <<a>>/*variable,readonly*/
           |<<object>>/*keyword*/ <<A>>/*class*/ {
           |  <<val>>/*keyword*/ <<age>>/*variable,readonly*/ = <<42>>/*number*/
           |  <<age>>/*variable,readonly*/ + <<12>>/*number*/
           |}
           |""".stripMargin,
        fileContents,
      )
    } yield ()
  }

  def check(firstSupported: String, lastSupported: String): Unit = {
    def isKnownScalaVersion(scalaVersion: String) = {
      val compilerDep =
        if (scalaVersion.startsWith("3."))
          Dependency.of("org.scala-lang", "scala3-compiler_3", scalaVersion)
        else
          Dependency.of("org.scala-lang", "scala-library", scalaVersion)

      val fetch = Try {
        Fetch
          .create()
          .withDependencies(compilerDep)
          .withMainArtifacts()
          .fetch()
      }
      !fetch.isFailure
    }

    // This doesn't try to fetch, which will fail for local version
    val testMtagsResolver = super.mtagsResolver

    def isSupported(ver: SemVer.Version) = {
      testMtagsResolver.isSupportedScalaVersion(ver.toString()) || mtagsResolver
        .isSupportedInOlderVersion(ver.toString())
    }

    def tick(ver: SemVer.Version) = {
      val updatedPatch = ver.copy(patch = ver.patch + 1)
      if (isKnownScalaVersion(updatedPatch.toString()))
        updatedPatch
      else
        ver.copy(minor = ver.minor + 1, patch = 0)
    }

    val version = SemVer.Version.fromString(firstSupported)
    val lastVersion = SemVer.Version.fromString(lastSupported)

    @tailrec
    def checkEachVersion(currentVersion: SemVer.Version): Unit = {
      if (currentVersion != lastVersion) {
        assert(
          isSupported(currentVersion),
          s"Did you forget to add last supported Metals version for $currentVersion in MtagsResolver?",
        )
        val next = tick(currentVersion)
        checkEachVersion(next)
      }
    }

    checkEachVersion(version)
  }
}
