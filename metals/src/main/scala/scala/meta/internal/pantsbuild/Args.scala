package scala.meta.internal.pantsbuild

import java.nio.file.Path
import scala.meta.internal.io.PathIO
import scala.meta.io.AbsolutePath
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.pc.CancelToken
import scala.util.Try
import scala.util.Failure
import scala.util.Success

/**
 * The command-line argument parser for BloopPants.
 */
case class Args(
    isHelp: Boolean = false,
    isCompile: Boolean = true,
    isCache: Boolean = false,
    isRegenerate: Boolean = false,
    isIntelliJ: Boolean = false,
    isLaunchIntelliJ: Boolean = false,
    maxFileCount: Int = 5000,
    workspace: Path = PathIO.workingDirectory.toNIO,
    out: Path = PathIO.workingDirectory.toNIO,
    targets: List[String] = Nil,
    token: CancelToken = EmptyCancelToken,
    onFilemap: Filemap => Unit = _ => Unit
) {
  def pants: AbsolutePath = AbsolutePath(workspace.resolve("pants"))
  def helpMessage: String =
    s"""pants-bloop [option ..] <target ..>
       |
       |Command-line tool to export a Pants build into Bloop JSON config files.
       |The <target ..> argument is a list of Pants targets to export,
       |for example "src/main/scala::".
       |
       |  --help
       |    Print this help message
       |  --workspace <dir>
       |    The directory containing the pants build.
       |  --out <dir>
       |    The directory containing the generated Bloop JSON files. Defaults to --workspace if not provided.
       |  --regenerate (default=false)
       |    If enabled, only re-expands the source globs to pick up new file creations.
       |  --[no-]cache (default=false)
       |    If enabled, cache the result from `./pants export`
       |  --[no-]compile (default=$isCompile)
       |    If enabled, do not run `./pants export-classpath`
       |  --max-file-count (default=$maxFileCount)
       |    The export process fails fast if the number of exported source files exceeds this threshold.
       |  --intellij
       |    Export Bloop project in empty sibling directory and open IntelliJ after export completes.
       |  --[no-]launch-intellij
       |    Launch IntelliJ after export completes. Default false unless --intellij is enabled.
       |
       |Example usage:
       |  pants-bloop myproject::                   # Export a single project
       |  pants-bloop myproject:: other-project::   # Export multiple projects
       |  pants-bloop --intellij myproject::        # Export a single project
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
        parse(tail, base.copy(out = AbsolutePath(out).toNIO))
      case "--compile" :: tail =>
        parse(tail, base.copy(isCompile = true))
      case "--regenerate" :: tail =>
        parse(tail, base.copy(isRegenerate = true))
      case "--no-compile" :: tail =>
        parse(tail, base.copy(isCompile = false))
      case "--intellij" :: tail =>
        parse(tail, base.copy(isIntelliJ = true, isLaunchIntelliJ = true))
      case "--launch-intellij" :: tail =>
        parse(tail, base.copy(isLaunchIntelliJ = true))
      case "--no-launch-intellij" :: tail =>
        parse(tail, base.copy(isLaunchIntelliJ = false))
      case "--no-cache" :: tail =>
        parse(tail, base.copy(isCache = false))
      case "--cache" :: tail =>
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
        Right(base.copy(targets = base.targets ++ tail))
    }
}
