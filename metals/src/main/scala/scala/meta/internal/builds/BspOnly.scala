package scala.meta.internal.builds

import scala.meta.io.AbsolutePath

/**
 * Build tool for custom bsp detected in `.bsp/<name>.json`
 */
case class BspOnly(
    override val executableName: String,
    override val projectRoot: AbsolutePath,
) extends BuildTool {
  override def digest(workspace: AbsolutePath): Option[String] = Some("OK")
  override val forcesBuildServer = true
}
