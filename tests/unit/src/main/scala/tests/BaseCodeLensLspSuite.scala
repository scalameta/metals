package tests

import scala.concurrent.Future

import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsServerConfig

import ch.epfl.scala.bsp4j.DebugSessionParams
import com.google.gson.JsonObject
import munit.Location
import munit.TestOptions
import org.eclipse.lsp4j.Command

abstract class BaseCodeLensLspSuite(
    name: String,
    initializer: BuildServerInitializer = QuickBuildInitializer,
) extends BaseLspSuite(name, initializer) {

  override def serverConfig: MetalsServerConfig =
    super.serverConfig.copy(loglevel = "debug")

  override def beforeEach(context: BeforeEach): Unit = {
    super.beforeEach(context)
    dapClient.touch()
    dapServer.touch()
    bspTrace.touch()
  }

  protected def runFromCommand(
      cmd: Command,
      javaHome: Option[String],
  ): Option[String] = {
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
            javaHome.foreach { home =>
              assert(
                cmd(0).toLowerCase().startsWith(home.toLowerCase()),
                s"${cmd(0)} didn't start with java home:\n$home ",
              )
            }
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

  protected def testRunShellCommand(
      name: String,
      javaHome: Option[String] = None,
  ): Unit =
    test(name) {
      def javaHomeString(javaHome: String) = {
        val escaped = javaHome.replace("\\", "\\\\")
        s"\"platformJavaHome\": \"$escaped\""
      }
      cleanWorkspace()
      for {
        _ <- initialize(
          s"""|/metals.json
              |{
              |  "a": { ${javaHome.map(javaHomeString).getOrElse("")} }
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
        _ = assertEquals(runFromCommand(command, javaHome), Some("Hello java!"))
      } yield ()
    }

  def check(
      name: TestOptions,
      library: Option[String] = None,
      plugin: Option[String] = None,
      scalaVersion: Option[String] = None,
      printCommand: Boolean = false,
      extraInitialization: (TestingServer, String) => Future[Unit] = (_, _) =>
        Future.unit,
      minExpectedLenses: Int = 1,
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

      def wrap(dep: Option[String]) = dep.map(s => s""" "$s" """).getOrElse("")
      for {
        _ <- initialize(
          s"""|/metals.json
              |{
              |  "a": { 
              |    "libraryDependencies" : [ ${wrap(library)} ],
              |    "compilerPlugins" : [ ${wrap(plugin)} ],
              |    "scalaVersion": "$actualScalaVersion"
              |  }
              |}
              |
              |/$sourceFile
              |$original
              |""".stripMargin
        )
        _ <- extraInitialization(server, sourceFile)
        _ <- assertCodeLenses(
          sourceFile,
          expected,
          printCommand = printCommand,
          minExpectedLenses = minExpectedLenses,
        )
      } yield ()
    }
  }

  def checkTestCases(
      name: TestOptions,
      library: Option[String] = None,
      plugin: Option[String] = None,
      scalaVersion: Option[String] = None,
      printCommand: Boolean = false,
      minExpectedLenses: Int = 1,
  )(
      expected: => String
  )(implicit loc: Location): Unit = check(
    name,
    library,
    plugin,
    scalaVersion,
    printCommand,
    (server, sourceFile) =>
      server.discoverTestSuites(List(sourceFile)).map(_ => ()),
    minExpectedLenses,
  )(expected)

  protected def assertCodeLenses(
      relativeFile: String,
      expected: String,
      maxRetries: Int = 4,
      printCommand: Boolean = false,
      minExpectedLenses: Int = 1,
  )(implicit loc: Location): Future[Unit] = {
    val obtained =
      server
        .codeLensesText(relativeFile, printCommand, minExpectedLenses)(
          maxRetries
        )
        .recover { case _: NoSuchElementException =>
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
