package tests.feature

import scala.meta.internal.metals
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.MetalsEnrichments._

import munit.TestOptions
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import tests.BaseLspSuite
import tests.BuildInfo
import tests.FileLayout
import tests.RangeReplace

class PcReferencesLspSuite
    extends BaseLspSuite("pc-references")
    with RangeReplace {

  check(
    "basic2",
    """|/a/src/main/scala/O.scala
       |object O {
       |  val <<i@@>> = 1
       |  val k = <<i>>
       |}
       |/a/src/main/scala/Main.scala
       |object Main {
       |  val g = O.<<i>>
       |}
       |""".stripMargin,
    metals.BuildInfo.scala213,
  )

  check(
    "basic3",
    """|/a/src/main/scala/O.scala
       |object O {
       |  val <<i@@>> = 1
       |  val k = <<i>>
       |}
       |/a/src/main/scala/Main.scala
       |object Main {
       |  val g = O.<<i>>
       |}
       |""".stripMargin,
    metals.BuildInfo.scala3,
  )

  def check(
      name: TestOptions,
      input: String,
      scalaVersion: String = BuildInfo.scalaVersion,
  ): Unit =
    test(name) {
      cleanWorkspace()
      val files = FileLayout.mapFromString(input)
      val defFile =
        files.collectFirst {
          case (pathStr, content) if content.contains("@@") => pathStr
        }.get

      def paramsF = {
        val content = files.get(defFile).get
        val actualContent = content.replaceAll("<<|>>", "")
        val context = new ReferenceContext(true)
        server.offsetParams(defFile, actualContent, workspace).map {
          case (_, params) =>
            new ReferenceParams(
              params.getTextDocument(),
              params.getPosition(),
              context,
            )
        }
      }

      def refFiles = files.keysIterator.map(server.toPath(_))

      val layout = input.replaceAll("<<|>>|@@", "")

      def renderObtained(refs: List[metals.ReferencesResult]): String = {
        val refsMap = refs
          .flatMap(_.locations)
          .groupMap(ref =>
            ref.getUri().toAbsolutePath.toRelative(workspace).toString
          )(_.getRange())
        files
          .map { case (pathStr, content) =>
            val actualContent = content.replaceAll("<<|>>", "")
            val withMarkings =
              if (pathStr == defFile) {
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
        _ <- server.didOpen(defFile)
        params <- paramsF
        refs <- server.server.compilers.references(
          params,
          refFiles,
          EmptyCancelToken,
        )
        _ = assertNoDiff(renderObtained(refs), input)
      } yield ()
    }
}
