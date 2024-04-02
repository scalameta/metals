package scala.meta.internal.builds

import java.security.MessageDigest

import scala.meta.internal.builds.Digest
import scala.meta.internal.mtags.MD5
import scala.meta.io.AbsolutePath

/**
 * Build tool for custom bsp detected in `.bsp/<name>.json` or `bspGlobalDirectories`
 */
case class BspOnly(
    override val executableName: String,
    override val projectRoot: AbsolutePath,
    pathToBspConfig: AbsolutePath,
) extends BuildTool {
  override def digest(workspace: AbsolutePath): Option[String] = {
    val digest = MessageDigest.getInstance("MD5")
    val isSuccess =
      Digest.digestJson(pathToBspConfig, digest)
    if (isSuccess) Some(MD5.bytesToHex(digest.digest()))
    else None
  }
  override val forcesBuildServer = true

  override def isBspGenerated(workspace: AbsolutePath): Boolean = true
}
