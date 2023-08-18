package tests

import scala.concurrent.Future

import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.MetalsEnrichments._

import ch.epfl.scala.bsp4j.DebugSessionParams
import com.google.gson.JsonObject
import munit.Location
import munit.TestOptions
import org.eclipse.lsp4j.Command

abstract class BaseCodeLensLspSuite(
    name: String,
    initializer: BuildServerInitializer = QuickBuildInitializer,
) extends BaseLspSuite(name, initializer) {

  protected def runFromCommand(cmd: Command): Option[String] = {
    cmd.getArguments().asScala.toList match {
      case (params: DebugSessionParams) :: _ =>
        params.getData() match {
          case obj: JsonObject =>
            val cmd = obj
              .get("shellCommand")
              .getAsString()
              // need to remove all escapes since ShellRunner does escaping itself
              // for windows
              .replace("\\\"", "")
              .replace("\"\\", "\\")
              // for linux
              .replace("/\"", "/")
              .replace("\"/", "/")
              // when testing on Windows Program Files is problematic when splitting into list
              .replace("Program Files", "ProgramFiles")
              .replace("run shell command", "runshellcommand")
              .split("\\s+")
              .map(
                // remove from escaped classpath
                _.stripPrefix("\"")
                  .stripSuffix("\"")
                  // Add back spaces
                  .replace("ProgramFiles", "Program Files")
                  .replace("runshellcommand", "run shell command")
              )
            ShellRunner
              .runSync(cmd.toList, workspace, redirectErrorOutput = false)
              .map(_.trim())
              .orElse {
                scribe.error(
                  "Couldn't run command specified in shellCommand."
                )
                scribe.error("The command run was:\n" + cmd.mkString(" "))
                None
              }
          case _ => None
        }

      case _ => None
    }
  }

  protected def testRunShellCommand(name: String): Unit =
    test(name) {
      cleanWorkspace()
      for {
        _ <- initialize(
          s"""|/metals.json
              |{
              |  "a": {}
              |}
              |/a/src/main/scala/a/Main.scala
              |package foo
              |
              |object Main {
              |  def main(args: Array[String]): Unit = {
              |     println("Hello java!")
              |  }
              |}
              |""".stripMargin
        )
        _ <- server.didOpen("a/src/main/scala/a/Main.scala")
        lenses <- server.codeLenses("a/src/main/scala/a/Main.scala")
        _ = assert(lenses.size > 0, "No lenses were generated!")
        command = lenses.head.getCommand()
        _ = assertEquals(runFromCommand(command), Some("Hello java!"))
      } yield ()
    }

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
