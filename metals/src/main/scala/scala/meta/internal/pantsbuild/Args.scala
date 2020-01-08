package scala.meta.internal.pantsbuild

import java.nio.file.Path
import scala.meta.internal.io.PathIO
import scala.meta.io.AbsolutePath
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.pc.CancelToken
import scala.util.Try
import scala.util.Failure
import scala.util.Success
import java.nio.file.Paths
import scala.meta.internal.metals.BuildInfo

/**
 * The command-line argument parser for BloopPants.
 */
case class Args(
    isHelp: Boolean = false,
    isCache: Boolean = false,
    isRegenerate: Boolean = false,
    isIntelliJ: Boolean = false,
    isVscode: Boolean = false,
    isLaunchIntelliJ: Boolean = false,
    maxFileCount: Int = 5000,
    workspace: Path = PathIO.workingDirectory.toNIO,
    out: Path = PathIO.workingDirectory.toNIO,
    targets: List[String] = Nil,
    token: CancelToken = EmptyCancelToken,
    onFilemap: Filemap => Unit = _ => Unit
) {
  def pants: AbsolutePath = AbsolutePath(workspace.resolve("pants"))
  def isWorkspaceAndOutputSameDirectory: Boolean =
    workspace == out
  def command: String = Option(System.getProperty("sun.java.command")) match {
    case Some(path) =>
      Try(Paths.get(path.split(" ").head).getFileName().toString())
        .getOrElse("pants-bloop")
    case _ => "pants-bloop"
  }
  def helpMessage: String =
    s"""$command ${BuildInfo.metalsVersion}
       |$command [option ..] <target ..>
       |
       |
       |Command-line tool to export a Pants build into Bloop JSON config files.
       |The <target ..> argument is a list of Pants targets to export,
       |for example "$command my-project:: another-project::".
       |
       |  --workspace <dir>
       |    The directory containing the pants build, defaults to the working directory.
       |  --out <dir>
       |    The directory containing the generated Bloop JSON files. Defaults to --workspace if not provided.
       |  --update
       |    Use this flag after updating $command to re-generate the Bloop JSON files.
       |  --max-file-count (default=$maxFileCount)
       |    The export process fails fast if the number of exported source files exceeds this threshold.
       |  --intellij
       |    Export Bloop project in empty sibling directory and open IntelliJ after export completes.
       |  --[no-]launch-intellij
       |    Launch IntelliJ after export completes. Default false unless --intellij is enabled.
       |
       |Example usage:
       |  $command myproject::                        # Export a single project
       |  $command myproject:: other-project::        # Export multiple projects
       |  $command --intellij myproject::             # Export a single project and launch IntelliJ
       |  $command --update myproject::               # Re-export after updating $command without re-calling Pants.
       |  $command --out subdirectory myproject::     # Generate Bloop JSON files in a subdirectory
       |  $command --max-file-count=10000 myproject:: # Increase the limit for number of files to export.
       |""".stripMargin
}
object Args {
  def parse(args: List[String]): Either[List[String], Args] =
    args match {
      case Nil => Right(Args(isHelp = true))
      case _ =>
        parse(args, Args()).map { parsed =>
          if (parsed.isIntelliJ) {
            val projectName = parsed.targets
              .map(_.stripSuffix("::").stripSuffix("/::"))
              .map(BloopPants.makeReadableFilename)
              .mkString("_")
            parsed.copy(
              out = parsed.workspace
                .getParent()
                .resolve("intellij-bsp")
                .resolve(projectName)
                .resolve(projectName)
            )
          } else {
            parsed
          }

        }
    }
  def parse(args: List[String], base: Args): Either[List[String], Args] =
    args match {
      case Nil => Right(base)
      case "--help" :: tail =>
        Right(Args(isHelp = true))
      case "--workspace" :: workspace :: tail =>
        val dir = AbsolutePath(workspace).toNIO
        val out =
          if (base.out == base.workspace) dir
          else base.out
        parse(tail, base.copy(workspace = dir, out = out))
      case "--out" :: out :: tail =>
        parse(
          tail,
          base.copy(out = AbsolutePath(out)(AbsolutePath(base.workspace)).toNIO)
        )
      case "--regenerate" :: tail =>
        parse(tail, base.copy(isRegenerate = true))
      case "--intellij" :: tail =>
        parse(tail, base.copy(isIntelliJ = true, isLaunchIntelliJ = true))
      case "--vscode" :: tail =>
        parse(tail, base.copy(isVscode = true))
      case "--launch-intellij" :: tail =>
        parse(tail, base.copy(isLaunchIntelliJ = true))
      case "--no-launch-intellij" :: tail =>
        parse(tail, base.copy(isLaunchIntelliJ = false))
      case ("--update" | "--cache") :: tail =>
        parse(tail, base.copy(isCache = true))
      case "--max-file-count" :: count :: tail =>
        Try(count.toInt) match {
          case Failure(_) =>
            Left(
              List(
                s"type mismatch: --max-file-count expected a number, got '$count'"
              )
            )
          case Success(value) =>
            parse(tail, base.copy(maxFileCount = value))
        }
      case tail =>
        tail.headOption match {
          case Some(flag) if flag.startsWith("-") =>
            Left(List(s"unknown flag: $flag\n" + base.helpMessage))
          case _ =>
            Right(base.copy(targets = base.targets ++ tail))
        }
    }
}
