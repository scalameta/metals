package tests.feature

import scala.meta.internal.metals
import scala.meta.internal.metals.MetalsEnrichments._

import munit.TestOptions
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import tests.BaseLspSuite
import tests.BuildInfo
import tests.FileLayout
import tests.RangeReplace

class PcReferencesLspSuite
    extends BaseLspSuite("pc-references")
    with RangeReplace {

  for {
    scalaVersion <- List(metals.BuildInfo.scala213)
  } {
    check(
      s"basic1_$scalaVersion",
      """|/a/src/main/scala/Defn.scala
         |package a
         |object O {
         |  val <<i@@>> = 1
         |  val k = <<i>>
         |}
         |/a/src/main/scala/Main.scala
         |package a
         |object Main {
         |  val g = O.<<i>>
         |}
         |""".stripMargin,
      scalaVersion,
    )

    check(
      s"basic2_$scalaVersion",
      """|/a/src/main/scala/Defn.scala
         |package a
         |object O {
         |  val <<i>> = 1
         |  val k = <<i>>
         |}
         |/a/src/main/scala/Main.scala
         |package a
         |object Main {
         |  val g = O.<<i@@>>
         |}
         |""".stripMargin,
      scalaVersion,
    )

    check(
      s"basic3_$scalaVersion",
      """|/a/src/main/scala/Defn.scala
         |package a
         |object O {
         |  val <<i@@>> = 1
         |}
         |/a/src/main/scala/Main.scala
         |package a
         |object Main {
         |  val g = O.<<i>>
         |}
         |""".stripMargin,
      scalaVersion,
    )

    check(
      s"basic4_$scalaVersion",
      """|/a/src/main/scala/Defn.scala
         |package a
         |object Foo {
         |  val bar@@ = 1
         |}
         |/a/src/main/scala/Main.scala
         |package a
         |object Main {
         |  val h = Foo.<<bar>>
         |}
         |""".stripMargin,
      scalaVersion,
      includeDefinition = false,
    )

    check(
      s"local_$scalaVersion",
      """|/a/src/main/scala/Defn.scala
         |package a
         |object O {
         |  val bar = 5
         |  def f = bar
         |  def foo = {
         |   val <<bar>> = 3
         |   <<ba@@r>> + 4
         | }
         |}
         |""".stripMargin,
      scalaVersion,
    )

    check(
      s"basic_go_to_def_$scalaVersion",
      """|/a/src/main/scala/Defn.scala
         |package a
         |object O {
         |  val na@@me = 1
         |  val k = <<name>>
         |}
         |/a/src/main/scala/Main.scala
         |package a
         |object Main {
         |  val g = O.<<name>>
         |}
         |""".stripMargin,
      scalaVersion,
      useGoToDef = true,
    )

    check(
      s"apply-test_$scalaVersion",
      """|/a/src/main/scala/Defn.scala
         |package a
         |class O(v: Int) { }
         |object O {
         |  def <<app@@ly>>() = new O(1)
         |}
         |/a/src/main/scala/Main.scala
         |package a
         |object Main {
         |  val g = <<O>>()
         |}
         |""".stripMargin,
      scalaVersion,
    )

    check(
      s"constructor_$scalaVersion",
      """|/a/src/main/scala/Defn.scala
         |package a
         |case class Name(<<val@@ue>>: String)
         |
         |/a/src/main/scala/Main.scala
         |package a
         |object Main {
         |  val name2 = new Name(<<value>> = "44")
         |}
         |""".stripMargin,
      scalaVersion,
    )
  }

  def check(
      name: TestOptions,
      input: String,
      scalaVersion: String = BuildInfo.scalaVersion,
      includeDefinition: Boolean = true,
      defnFileName: String = "a/src/main/scala/Defn.scala",
      useGoToDef: Boolean = false,
  )(implicit loc: munit.Location): Unit =
    test(name) {
      cleanWorkspace()
      val fullInput =
        s"""|$input
            |/a/src/main/scala/Error.scala
            |package a
            |object Error {
            |  val foo: Int = ""
            |}
            |""".stripMargin
      val files = FileLayout.mapFromString(fullInput)
      val focusFile =
        files.collectFirst {
          case (pathStr, content) if content.contains("@@") => pathStr
        }.get

      def paramsF = {
        val content = files.get(focusFile).get
        val actualContent = content.replaceAll("<<|>>", "")
        val context = new ReferenceContext(includeDefinition)
        server.offsetParams(focusFile, actualContent, workspace).map {
          case (_, params) =>
            new ReferenceParams(
              params.getTextDocument(),
              params.getPosition(),
              context,
            )
        }
      }

      val layout = fullInput.replaceAll("<<|>>|@@", "")

      def renderObtained(refs: List[Location]): String = {
        val refsMap = refs
          .groupMap(ref =>
            ref.getUri().toAbsolutePath.toRelative(workspace).toString
          )(_.getRange())
        files
          .map { case (pathStr, content) =>
            val actualContent = content.replaceAll("<<|>>", "")
            val withMarkings =
              if (pathStr == focusFile) {
                val index = actualContent.indexOf("@@")
                val code = actualContent.replaceAll("@@", "")
                renderRangesAsString(
                  code,
                  refsMap.getOrElse(pathStr, Nil),
                  List((index, 2)),
                  Some(actualContent),
                )
              } else {
                renderRangesAsString(
                  actualContent,
                  refsMap.getOrElse(pathStr, Nil),
                )
              }
            s"""|/$pathStr
                |$withMarkings""".stripMargin
          }
          .mkString("\n")
      }

      for {
        _ <- initialize(
          s"""|/metals.json
              |{
              |  "a" : {
              |    "scalaVersion": "$scalaVersion"
              |  }
              |}
              |$layout""".stripMargin
        )
        _ <- server.didOpen(defnFileName)
        defnFileContent = files
          .get(defnFileName)
          .map(_.replaceAll("<<|>>|@@", ""))
          .getOrElse("")
        // load the file with definition to pc
        _ <- server.hover(defnFileName, s"@@$defnFileContent", workspace)
        _ <- server.didOpen(focusFile)
        params <- paramsF
        refs <-
          if (!useGoToDef) server.server.references(params).asScala
          else server.server.definition(params).asScala
        _ = assertNoDiff(renderObtained(refs.asScala.toList), fullInput)
      } yield ()
    }
}
