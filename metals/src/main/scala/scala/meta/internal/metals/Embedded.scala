package scala.meta.internal.metals

import java.nio.file.Files
import java.nio.file.Path
import scala.meta.io.AbsolutePath

/**
 * Wrapper around software that is embedded with Metals.
 *
 * - sbt-launch.jar
 * - bloop.py
 * - ch.epfl.scala:bloop-frontend
 */
final class Embedded(
    icons: Icons,
    statusBar: StatusBar,
    userConfig: () => UserConfiguration
) {

  private lazy val tmp: Path = {
    val dir = Files.createTempDirectory("metals")
    dir.toFile.deleteOnExit()
    dir
  }

  private def copyResource(name: String): AbsolutePath = {
    val in = this.getClass.getResourceAsStream(s"/$name")
    val out = tmp.resolve(name)
    Files.copy(in, out)
    AbsolutePath(out)
  }

  /**
   * Returns path to a local copy of sbt-launch.jar.
   *
   * We use embedded sbt-launch.jar instead of user `sbt` command because
   * we can't rely on `sbt` resolving correctly when using system processes, at least
   * it failed on Windows when I tried it.
   */
  lazy val embeddedSbtLauncher: AbsolutePath =
    copyResource("sbt-launch.jar")

  /**
   * Returns local path to a `bloop.py` script that we can call as `python bloop.py`.
   *
   * We don't `sys.process("bloop", ...)` directly because that requires bloop to be
   * available on the PATH of the forked process and that didn't work while testing
   * on Windows (even if `bloop` worked fine in the git bash).
   */
  lazy val bloopPy: AbsolutePath =
    copyResource("bloop.py")

  /**
   * The Bloop launcher bootstrap script: https://scalacenter.github.io/bloop/docs/launcher-reference
   */
  lazy val embeddedBloopLauncher: AbsolutePath =
    copyResource("bloop-launch.jar")

}
