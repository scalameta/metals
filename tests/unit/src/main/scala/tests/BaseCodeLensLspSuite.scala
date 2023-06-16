package tests

import scala.concurrent.Future

import munit.Location
import munit.TestOptions

abstract class BaseCodeLensLspSuite(name: String) extends BaseLspSuite(name) {

  def check(
      name: TestOptions,
      library: Option[String] = None,
      scalaVersion: Option[String] = None,
      printCommand: Boolean = false,
      extraInitialization: (TestingServer, String) => Future[Unit] = (_, _) =>
        Future.unit,
  )(
      expected: => String
  )(implicit loc: Location): Unit = {
    test(name) {
      cleanWorkspace()
      val original = expected.replaceAll("(<<.*>>[ \t]*)+(\n|\r\n)", "")
      val actualScalaVersion = scalaVersion.getOrElse(BuildInfo.scalaVersion)
      val sourceFile = {
        val file = """package (.*).*""".r
          .findFirstMatchIn(original)
          .map(_.group(1))
          .map(packageName => packageName.replaceAll("\\.", "/"))
          .map(packageDir => s"$packageDir/Foo.scala")
          .getOrElse("Foo.scala")

        s"a/src/main/scala/$file"
      }

      val libraryString = library.map(s => s""" "$s" """).getOrElse("")
      for {
        _ <- initialize(
          s"""|/metals.json
              |{
              |  "a": { 
              |    "libraryDependencies" : [ $libraryString ],
              |    "scalaVersion": "$actualScalaVersion"
              |  }
              |}
              |
              |/$sourceFile
              |$original
              |""".stripMargin
        )
        _ <- extraInitialization(server, sourceFile)
        _ <- assertCodeLenses(sourceFile, expected, printCommand = printCommand)
      } yield ()
    }
  }

  def checkTestCases(
      name: TestOptions,
      library: Option[String] = None,
      scalaVersion: Option[String] = None,
      printCommand: Boolean = false,
  )(
      expected: => String
  )(implicit loc: Location): Unit = check(
    name,
    library,
    scalaVersion,
    printCommand,
    (server, sourceFile) =>
      server.discoverTestSuites(List(sourceFile)).map(_ => ()),
  )(expected)

  protected def assertCodeLenses(
      relativeFile: String,
      expected: String,
      maxRetries: Int = 4,
      printCommand: Boolean = false,
  )(implicit loc: Location): Future[Unit] = {
    val obtained =
      server.codeLensesText(relativeFile, printCommand)(maxRetries).recover {
        case _: NoSuchElementException =>
          server.textContents(relativeFile)
      }

    obtained.map(assertNoDiff(_, expected))
  }

  protected def assertNoCodeLenses(
      relativeFile: String,
      maxRetries: Int = 4,
  ): Future[Unit] = {
    server.codeLensesText(relativeFile)(maxRetries).failed.flatMap {
      case _: NoSuchElementException => Future.unit
      case e => Future.failed(e)
    }
  }
}
