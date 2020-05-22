package tests

import scala.concurrent.Future

import munit.Location
import munit.TestOptions

class BaseCodeLensLspSuite(name: String) extends BaseLspSuite(name) {

  def check(
      name: TestOptions,
      library: Option[String] = None,
      scalaVersion: Option[String] = None
  )(
      expected: String
  )(implicit loc: Location): Unit = {
    test(name) {
      cleanWorkspace()
      val original = expected.replaceAll("<<.*>>[^a-zA-Z0-9@]+", "")

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
        _ <- server.initialize(
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
        _ <- assertCodeLenses(sourceFile, expected)
      } yield ()
    }
  }

  protected def assertCodeLenses(
      relativeFile: String,
      expected: String,
      maxRetries: Int = 4
  )(implicit loc: Location): Future[Unit] = {
    val obtained = server.codeLenses(relativeFile)(maxRetries).recover {
      case _: NoSuchElementException =>
        server.textContents(relativeFile)
    }

    obtained.map(assertNoDiff(_, expected))
  }

  protected def assertNoCodeLenses(
      relativeFile: String,
      maxRetries: Int = 4
  ): Future[Unit] = {
    server.codeLenses(relativeFile)(maxRetries).failed.flatMap {
      case _: NoSuchElementException => Future.unit
      case e => Future.failed(e)
    }
  }
}
