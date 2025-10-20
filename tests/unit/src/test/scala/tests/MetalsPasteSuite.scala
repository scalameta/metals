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
        |/a/src/main/scala/example/from/FromFile.scala
        |package example.from
        |
        |import example.utils.Bar
        |
        |object Foo {
        |  val j = 3
        |}
        |
        |object Main {
        |  <<from<<val set: Set[Int] = Set(Foo.j, Bar.k, Bar(3).i)>>from>>
        |}
        |/a/src/main/scala/example/to/ToFile.scala
        |package to
        |
        |import example.from.Foo
        |import example.utils.Bar
        |
        |object Copy {
        |  <<to<<>>to>>
        |}
        |""".stripMargin,
    """|
       |package to
       |
       |import example.from.Foo
       |import example.utils.Bar
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
        |
        |/a/src/main/scala/example/from/FromFile.scala
        |package example.from
        |
        |import example.utils.{Bar => Baz}
        |
        |object Foo {
        |  val j = 3
        |}
        |
        |object Main {
        |  <<from<<val set: Set[Int] = Set(Foo.j, Baz.k, Baz(3).i)>>from>>
        |}
        |
        |/a/src/main/scala/example/to/ToFile.scala
        |package to
        |import scala.util.Try
        |
        |object Copy {
        |  def t = Try(true)
        |  <<to<<>>to>>
        |}
        |""".stripMargin,
    """|package to
       |import scala.util.Try
       |import example.from.Foo
       |import example.utils.{Bar => Baz}
       |
       |
       |object Copy {
       |  def t = Try(true)
       |  val set: Set[Int] = Set(Foo.j, Baz.k, Baz(3).i)
       |}
       |
       |""".stripMargin,
  )

  private def cleanupMarkers(content: String) = {
    content.replaceAll("(<<\\w+<<)|(>>\\w+>>)", "")
  }
  def checkCopyEdit(
      name: TestOptions,
      layout: String,
      expected: String,
  )(implicit loc: Location): Unit =
    test(name) {
      cleanWorkspace()
      val fromMarkerStart = "<<from<<"
      val fromMarkerEnd = ">>from>>"
      val files = FileLayout.mapFromString(layout)
      val (originFile, originFileContent) = files
        .find { case (_, content) =>
          content.contains(fromMarkerStart)
        }
        .getOrElse(
          throw new RuntimeException(s"No $fromMarkerStart marker specified")
        )

      val originStartRange = originFileContent.indexOf(fromMarkerStart)
      assert(
        originStartRange >= 0,
        s"No $fromMarkerStart found in origin file.",
      )
      val originEndRange =
        originFileContent.indexOf(fromMarkerEnd) - fromMarkerEnd.length()
      assert(originEndRange >= 0, s"No $fromMarkerEnd found in origin file.")

      val copiedText = cleanupMarkers(originFileContent)
        .drop(originStartRange)
        .take(originEndRange - originStartRange)

      val toMarkerStart = "<<to<<"
      val toMarkerEnd = ">>to>>"
      val (destinationFile, destinationFileContent) = files
        .find { case (_, content) =>
          content.contains(toMarkerStart)
        }
        .getOrElse(throw new RuntimeException("No from marker specified"))

      val destinationRangeStart = destinationFileContent.indexOf(toMarkerStart)
      assert(
        destinationRangeStart >= 0,
        assert(
          destinationRangeStart >= 0,
          s"No $toMarkerStart found in destination file.",
        ),
      )

      val destinationRangeEnd =
        destinationFileContent.indexOf(toMarkerEnd) - toMarkerStart.length()
      assert(
        destinationRangeEnd >= 0,
        assert(
          destinationRangeEnd >= 0,
          s"No $toMarkerEnd found in destination file.",
        ),
      )

      val afterCopiedContent = cleanupMarkers(
        destinationFileContent.take(
          destinationRangeStart
        ) + copiedText + destinationFileContent.drop(destinationRangeStart)
      )

      val copiedRange = Position
        .Range(
          Input.VirtualFile(destinationFile, afterCopiedContent),
          destinationRangeStart,
          destinationRangeStart + copiedText.length(),
        )
        .toLsp

      val originPosition = Position
        .Range(
          Input.VirtualFile(originFile, cleanupMarkers(originFileContent)),
          originStartRange,
          originEndRange,
        )
        .toLsp
        .getStart()

      val cleanedUpLayout = layout.replaceAll("(<<\\w+<<)|(>>\\w+>>)", "")

      for {
        _ <- initialize(cleanedUpLayout)
        _ <- server.didOpen(originFile)
        _ <- server.didOpen(destinationFile)
        _ <- server.didChange(destinationFile) { _ => afterCopiedContent }
        copyToUri = server.toPath(destinationFile).toURI.toString()
        originUri = server.toPath(originFile).toURI.toString()
        _ <- server.executeCommand(
          ServerCommands.MetalsPaste,
          MetalsPasteParams(
            new TextDocumentIdentifier(copyToUri),
            copiedRange,
            afterCopiedContent,
            new TextDocumentIdentifier(originUri),
            originPosition,
          ),
        )
        _ = assertNoDiff(
          server.buffers.get(server.toPath(destinationFile)).get,
          expected,
        )
      } yield ()
    }

}
