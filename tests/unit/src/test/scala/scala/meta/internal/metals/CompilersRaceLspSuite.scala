package scala.meta.internal.metals

import tests.BaseCompletionLspSuite

class CompilersRaceLspSuite extends BaseCompletionLspSuite("compilers-race") {

  override def userConfig: UserConfiguration =
    super.userConfig.copy(
      presentationCompilerDiagnostics = true,
      buildOnChange = false,
      buildOnFocus = false,
    )

  private val filename = "a/src/main/scala/a/A.scala"
  private val goodText =
    """|package a
       |object A {
       |  val value: String = "ok"
       |}
       |""".stripMargin
  private val badText =
    """|package a
       |object A {
       |  val value: String = 1
       |}
       |""".stripMargin

  test("cancel-retries-stale-did-focus-diagnostics") {
    cleanWorkspace()

    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/$filename
           |$goodText
           |""".stripMargin
      )
      _ <- server.didOpen(filename)
      _ = assertNoDiagnostics()
      path = server.toPath(filename)
      _ = server.buffers.put(path, badText)
      didFocus = server.didFocus(filename)
      _ = server.buffers.put(path, goodText)
      _ = server.server.compilers.cancel()
      _ = server.server.diagnostics.reset(Seq(path))
      _ <- didFocus
      _ = assertNoDiagnostics()
    } yield ()
  }

  test("cancel-drops-stale-did-change-diagnostics") {
    cleanWorkspace()

    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/$filename
           |$goodText
           |""".stripMargin
      )
      _ <- server.didOpen(filename)
      _ = assertNoDiagnostics()
      path = server.toPath(filename)
      didChange = server.didChange(filename)(_ => badText)
      _ = server.buffers.put(path, goodText)
      _ = server.server.compilers.cancel()
      _ = server.server.diagnostics.reset(Seq(path))
      _ <- didChange
      _ = assertNoDiagnostics()
    } yield ()
  }
}
