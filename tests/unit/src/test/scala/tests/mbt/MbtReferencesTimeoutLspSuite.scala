package tests.mbt

import scala.meta.internal.metals.AutoImportBuildKind
import scala.meta.internal.metals.Configs.MbtConfig
import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mbt.MbtBuildServer

import tests.BuildInfo
import tests.TestingServer

class MbtReferencesTimeoutLspSuite
    extends BaseMbtReferenceSuite("mbt-references-timeout") {

  override protected def initializationOptions: Option[InitializationOptions] =
    Some(TestingServer.TestDefault)

  override def userConfig: UserConfiguration =
    super.userConfig.copy(
      fallbackScalaVersion = Some(BuildInfo.scalaVersion),
      preferredBuildServer = Some(MbtBuildServer.name),
      automaticImportBuild = AutoImportBuildKind.All,
      mbtConfig = MbtConfig(
        importGeneratedSources = false,
        semanticdbCacheEnabled = false,
        semanticdbCacheMaxSize = Int.MaxValue,
        referencesTimeoutSeconds = 0,
      ),
    )

  private def extraUsages(count: Int): String =
    (1 to count).map { i =>
      s"""|/a/src/main/scala/a/Use$i.scala
          |package a
          |object Use$i {
          |  def go = Foo.target
          |}
          |""".stripMargin
    }.mkString

  testLSP("find-refs-incomplete") {
    cleanWorkspace()
    val definition = "a/src/main/scala/a/Foo.scala"
    for {
      _ <- initialize(
        s"""|/.metals/mbt.json
            |{}
            |/$definition
            |package a
            |object Foo {
            |  def target = 1
            |}
            |${extraUsages(20)}
            |""".stripMargin
      )
      _ <- server.didOpenAndFocus(definition)
      locations <- server.referencesSubquery(definition, "def tar@@get")
      _ = assert(
        locations.nonEmpty,
        "timed-out find-refs should still return locations from the current file",
      )
      _ = assertNoDiff(
        client.workspaceLogMessages,
        """|Warning: References incomplete (0/21 files). Nearby files were searched first.
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("rename-aborted") {
    cleanWorkspace()
    val definition = "a/src/main/scala/a/Foo.scala"
    val usage = "a/src/main/scala/a/Use1.scala"
    for {
      _ <- initialize(
        s"""|/.metals/mbt.json
            |{}
            |/$definition
            |package a
            |object Foo {
            |  def target = 1
            |}
            |${extraUsages(20)}
            |""".stripMargin
      )
      _ <- server.didOpenAndFocus(definition)
      renamed <- server.rename(
        definition,
        """|package a
           |object Foo {
           |  def tar@@get = 1
           |}
           |""".stripMargin,
        Set(definition, usage),
        "renamed",
      )
      // rename should not happen
      _ = assertNoDiff(
        renamed(definition),
        """|package a
           |object Foo {
           |  def target = 1
           |}
           |""".stripMargin,
      )
      _ = assertNoDiff(
        renamed(usage),
        """|package a
           |object Use1 {
           |  def go = Foo.target
           |}
           |""".stripMargin,
      )
      _ = assertNoDiff(
        client.workspaceErrorShowMessages,
        """|Rename aborted because find-references timed out after searching 0/21 files.
           |""".stripMargin,
      )
    } yield ()
  }
}
