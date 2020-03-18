package scala.meta.internal.pantsbuild.commands

import metaconfig.generic
import metaconfig.annotation._
import metaconfig.generic.Settings
import metaconfig.{ConfDecoder, ConfEncoder}
import java.nio.file.Path

case class OpenOptions(
    @Description("Open IntelliJ in the given project")
    intellij: Boolean = false,
    @Description("The IntelliJ application or binary to use for launching")
    intellijLauncher: Option[String] = None,
    @Description("Open VS Code in the given project")
    vscode: Boolean = false,
    @Hidden()
    @ExtraName("remainingArgs")
    projects: List[String] = Nil,
    @Hidden()
    @Description("If true, fails the command if no editor should be opened.")
    strict: Boolean = true,
    @Hidden()
    @Inline common: SharedOptions = SharedOptions.default
) {
  def withProject(project: Project): OpenOptions =
    copy(projects = List(project.name))
  def withWorkspace(workspace: Path): OpenOptions =
    copy(common = common.copy(workspace = workspace))
  def isEmpty: Boolean = !intellij && !vscode
}

object OpenOptions {
  val default: OpenOptions = OpenOptions()
  implicit lazy val surface: generic.Surface[OpenOptions] =
    generic.deriveSurface[OpenOptions]
  implicit lazy val encoder: ConfEncoder[OpenOptions] =
    generic.deriveEncoder[OpenOptions]
  implicit lazy val decoder: ConfDecoder[OpenOptions] =
    generic.deriveDecoder[OpenOptions](default)
  implicit lazy val settings: Settings[OpenOptions] = Settings[OpenOptions]
}
