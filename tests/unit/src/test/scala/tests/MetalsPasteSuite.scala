package tests
import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsPasteParams
import scala.meta.internal.metals.ServerCommands

import munit.Location
import munit.TestOptions
import org.eclipse.lsp4j.TextDocumentIdentifier

class MetalsPasteSuite
    extends BaseLspSuite("metals-paste", QuickBuildInitializer) {

  checkCopyEdit(
    "basic",
    s"""|/metals.json
        |{ "a" : { "scalaVersion": "${BuildInfo.scalaVersion}"}}
        |/a/src/main/scala/utils/Utils.scala
        |package example.utils
        |
        |case class Bar(i: Int)
        |object Bar {
        | def k = 2
        |}
        |""".stripMargin,
    """|package example.from
       |
       |import example.utils.Bar
       |
       |object Foo {
       |  val j = 3
       |}
       |
       |object Main {
       |  <<val set: Set[Int] = Set(Foo.j, Bar.k, Bar(3).i)>>
       |}
       |""".stripMargin,
    """|package example
       |
       |<<import example.from.Foo>>
       |<<import example.utils.Bar>>
       |
       |object Copy {
       |  val set: Set[Int] = Set(Foo.j, Bar.k, Bar(3).i)
       |}
       |""".stripMargin,
  )

  checkCopyEdit(
    "with-rename",
    s"""|/metals.json
        |{ "a" : { "scalaVersion": "${BuildInfo.scalaVersion}"}}
        |/a/src/main/scala/utils/Utils.scala
        |package example.utils
        |
        |case class Bar(i: Int)
        |object Bar {
        | def k = 2
        |}
        |""".stripMargin,
    """|package example.from
       |
       |import example.utils.{Bar => Baz}
       |
       |object Foo {
       |  val j = 3
       |}
       |
       |object Main {
       |  <<val set: Set[Int] = Set(Foo.j, Baz.k, Baz(3).i)>>
       |}
       |""".stripMargin,
    """|package example
       |
       |import scala.util.Try
       |<<import example.from.Foo>>
       |<<import example.utils.{Bar => Baz}>>
       |
       |object Copy {
       |  def t = Try(true)
       |  val set: Set[Int] = Set(Foo.j, Baz.k, Baz(3).i)
       |}
       |""".stripMargin,
  )

  def checkCopyEdit(
      name: TestOptions,
      layout: String,
      originFile: String,
      copyToFile: String,
  )(implicit loc: Location): Unit =
    test(name) {
      cleanWorkspace()
      val copyToPath = "a/src/main/scala/to/CopyTo.scala"
      val originPath = "a/src/main/scala/from/CopyFrom.scala"

      val copyToContent = copyToFile.replaceAll("<<.*>>\\n*", "")
      val copyToExpected = copyToFile.replaceAll("<<|>>", "")
      val originContent = originFile.replaceAll("<<|>>", "")

      val originStartRange = originFile.indexOf("<<")
      assert(originStartRange >= 0, "No << found in origin file.")
      val originEndRange = originFile.indexOf(">>") - 2
      assert(originEndRange >= 0, "No >> found in origin file.")
      val copiedText = originContent
        .drop(originStartRange)
        .take(originEndRange - originStartRange)

      val copiedStart = copyToContent.indexOf(copiedText)
      assert(
        copiedStart >= 0,
        s"Not found copied text: $copiedText in origin file",
      )
      val copiedEndRange = copiedStart + copiedText.length()

      val copiedRange = Position
        .Range(
          Input.VirtualFile(copyToPath, copyToContent),
          copiedStart,
          copiedEndRange,
        )
        .toLsp

      val originPosition = Position
        .Range(
          Input.VirtualFile(originPath, originContent),
          originStartRange,
          originStartRange,
        )
        .toLsp
        .getStart()

      for {
        _ <- initialize(
          s"""|$layout
              |/$originPath
              |$originContent
              |/$copyToPath
              |${copyToContent.replace(copiedText, "")}
              |""".stripMargin
        )
        _ <- server.didOpen(copyToPath)
        _ <- server.didOpen("a/src/main/scala/to/CopyTo.scala")
        _ <- server.didChange(copyToPath) { _ => copyToContent }
        copyToUri = server.toPath(copyToPath).toURI.toString()
        originUri = server.toPath(originPath).toURI.toString()
        _ <- server.executeCommand(
          ServerCommands.MetalsPaste,
          MetalsPasteParams(
            new TextDocumentIdentifier(copyToUri),
            copiedRange,
            new TextDocumentIdentifier(originUri),
            originPosition,
          ),
        )
        _ = assertNoDiff(
          server.buffers.get(server.toPath(copyToPath)).get,
          copyToExpected,
        )
      } yield ()
    }

}
