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
        | def apply(str: String): Bar = Bar(str.toInt)
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
        |  <<from<<val set: Set[Int] = Set(Foo.j, Baz.k, Baz(3).i, Baz("4").i)>>from>>
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
       |object Copy {
       |  def t = Try(true)
       |  val set: Set[Int] = Set(Foo.j, Baz.k, Baz(3).i, Baz("4").i)
       |}
       |
       |""".stripMargin,
  )

  checkCopyEdit(
    "with-rename-package",
    s"""|/metals.json
        |{ "a" : { "scalaVersion": "${BuildInfo.scalaVersion}"}}
        |/a/src/main/scala/utils/Utils.scala
        |package example.utils
        |
        |case class Bar(i: Int)
        |object Bar {
        | def k = 2
        | def apply(str: String): Bar = Bar(str.toInt)
        |}
        |
        |/a/src/main/scala/example/from/FromFile.scala
        |package example.from
        |
        |import example.{utils => u}
        |
        |object Foo {
        |  val j = 3
        |}
        |
        |object Main {
        |  <<from<<val set: Set[Int] = Set(Foo.j, u.Bar.k, u.Bar(3).i, u.Bar("4").i)>>from>>
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
       |import example.{utils => u}
       |import example.from.Foo
       |
       |object Copy {
       |  def t = Try(true)
       |  val set: Set[Int] = Set(Foo.j, u.Bar.k, u.Bar(3).i, u.Bar("4").i)
       |}
       |
       |
       |""".stripMargin,
  )

  checkCopyEdit(
    "with-rename-package2",
    s"""|/metals.json
        |{ "a" : { "scalaVersion": "${BuildInfo.scalaVersion}"}}
        |/a/src/main/scala/utils/Utils.scala
        |package example.utils
        |
        |case class Bar(i: Int)
        |object Bar {
        | def k = 2
        | def apply(str: String): Bar = Bar(str.toInt)
        |}
        |
        |/a/src/main/scala/example/from/FromFile.scala
        |package example.from
        |
        |import example.{utils => u}
        |
        |object Foo {
        |  val j = 3
        |}
        |
        |object Main {
        |  <<from<<val set: Set[Int] = Set(Foo.j, u.Bar.k, u.Bar(3).i, u.Bar("4").i)>>from>>
        |}
        |
        |/a/src/main/scala/example/to/ToFile.scala
        |package to
        |
        |object Copy {
        |  <<to<<>>to>>
        |}
        |""".stripMargin,
    """|package to
       |
       |import example.from.Foo
       |
       |import example.{utils => u}
       |
       |object Copy {
       |  val set: Set[Int] = Set(Foo.j, u.Bar.k, u.Bar(3).i, u.Bar("4").i)
       |}
       |
       |
       |""".stripMargin,
  )

  checkCopyEdit(
    "same-file",
    s"""|/metals.json
        |{ "a" : { "scalaVersion": "${BuildInfo.scalaVersion}"}}
        |/a/src/main/scala/utils/Utils.scala
        |package example.utils
        |
        |object DefinitionProvider {
        |  
        |  def definition(path: String, token: String): Future[Int] = {
        |    Future.successful(1)
        |  }
        |}
        |/a/src/main/scala/example/from/FromFile.scala
        |package example.from
        |
        |class Foo2 {
        |  import example.utils.DefinitionProvider
        |  val definition = DefinitionProvider.definition
        |  for {
        |    a <- List(1)
        |    b <- <<from<<definition("example.from.FromFile", token)>>from>>
        |  } yield {
        |    a + b
        |  }
        |}
        |
        |class Foo {
        |  for {
        |    a <- List(1)
        |    b <- <<to<<>>to>>
        |  } yield {
        |    a + b
        |  }
        |}
        |
        |""".stripMargin,
    """|package example.from
       |
       |class Foo2 {
       |  import example.utils.DefinitionProvider
       |  val definition = DefinitionProvider.definition
       |  for {
       |    a <- List(1)
       |    b <- definition("example.from.FromFile", token)
       |  } yield {
       |    a + b
       |  }
       |}
       |
       |class Foo {
       |  for {
       |    a <- List(1)
       |    b <- definition("example.from.FromFile", token)
       |  } yield {
       |    a + b
       |  }
       |}
       |
       |""".stripMargin,
  )

  checkCopyEdit(
    "method-import",
    s"""|/metals.json
        |{ "a" : { "scalaVersion": "${BuildInfo.scalaVersion}"}}
        |/a/src/main/scala/utils/Utils.scala
        |package example.utils
        |
        |object TestObject {
        |  def testMethod(testParam: String): Int = 42
        |  val testVal = 123
        |}
        |/a/src/main/scala/example/from/FromFile.scala
        |package example.from
        |
        |import example.utils.TestObject.testMethod
        |import example.utils.TestObject.testVal
        |
        |object Main {
        |  <<from<<val x = testMethod("123") 
        |  val y = testVal>>from>>
        |}
        |
        |/a/src/main/scala/example/to/ToFile.scala
        |package to
        |
        |object Copy {
        |  <<to<<>>to>>
        |}
        |""".stripMargin,
    """|package to
       |
       |import example.utils.TestObject.testMethod
       |import example.utils.TestObject.testVal
       |
       |object Copy {
       |  val x = testMethod("123") 
       |  val y = testVal
       |}
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
      val toMarkerStart = "<<to<<"
      val toMarkerEnd = ">>to>>"

      val files = FileLayout.mapFromString(layout)
      val (originFile, originFileContent) = files
        .find { case (_, content) =>
          content.contains(fromMarkerStart)
        }
        .getOrElse(
          throw new RuntimeException(s"No $fromMarkerStart marker specified")
        )

      val cleanedUpOrigin =
        originFileContent.replaceAll(s"($toMarkerStart)|($toMarkerEnd)", "")
      val originStartRange = cleanedUpOrigin.indexOf(fromMarkerStart)
      assert(
        originStartRange >= 0,
        s"No $fromMarkerStart found in origin file.",
      )
      val originEndRange =
        cleanedUpOrigin.indexOf(fromMarkerEnd) - fromMarkerEnd.length()
      assert(originEndRange >= 0, s"No $fromMarkerEnd found in origin file.")

      val copiedText = cleanupMarkers(cleanedUpOrigin)
        .drop(originStartRange)
        .take(originEndRange - originStartRange)

      val (destinationFile, destinationFileContent) = files
        .find { case (_, content) =>
          content.contains(toMarkerStart)
        }
        .getOrElse(throw new RuntimeException("No from marker specified"))
      val cleanedUpDestination = destinationFileContent.replaceAll(
        s"($fromMarkerStart)|($fromMarkerEnd)",
        "",
      )
      val destinationRangeStart = cleanedUpDestination.indexOf(toMarkerStart)
      assert(
        destinationRangeStart >= 0,
        assert(
          destinationRangeStart >= 0,
          s"No $toMarkerStart found in destination file.",
        ),
      )

      val destinationRangeEnd =
        cleanedUpDestination.indexOf(toMarkerEnd) - toMarkerStart.length()
      assert(
        destinationRangeEnd >= 0,
        assert(
          destinationRangeEnd >= 0,
          s"No $toMarkerEnd found in destination file.",
        ),
      )

      val afterCopiedContent = cleanupMarkers(
        cleanedUpDestination.take(
          destinationRangeStart
        ) + copiedText + cleanedUpDestination.drop(
          destinationRangeStart + toMarkerStart.length()
        )
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
