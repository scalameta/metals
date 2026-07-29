package scala.meta.internal.metals

import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

import scala.collection.concurrent.TrieMap
import scala.util.Properties
import scala.util.control.NonFatal

import scala.meta.io.AbsolutePath

import bloop.rifle.BloopRifleConfig
import bloop.rifle.BloopRifleLogger
import bloop.rifle.BspConnectionAddress

class BloopServerConfigFactory(
    bloopWorkingDir: AbsolutePath,
    bloopDaemonDir: AbsolutePath,
    bloopLogger: BloopRifleLogger,
) {

  private val folderIdMap = TrieMap.empty[AbsolutePath, Int]

  def bloopConfig(
      userConfig: Option[UserConfiguration],
      projectRoot: Option[AbsolutePath],
  ): BloopRifleConfig = {

    val addr = BloopRifleConfig.Address.DomainSocket(
      bloopDaemonDir.toNIO
    )

    val config = BloopRifleConfig
      .default(addr, BloopServers.fetchBloop _, bloopWorkingDir.toNIO.toFile)
      .copy(
        bspSocketOrPort = Some { () =>
          val pid =
            ManagementFactory.getRuntimeMXBean.getName.takeWhile(_ != '@').toInt
          val dir = bloopWorkingDir.resolve("bsp").toNIO
          if (!Files.exists(dir)) {
            Files.createDirectories(dir.getParent)
            if (Properties.isWin)
              Files.createDirectory(dir)
            else
              Files.createDirectory(
                dir,
                PosixFilePermissions
                  .asFileAttribute(PosixFilePermissions.fromString("rwx------")),
              )
          }
          // We need to use a different socket for each folder, since it's a separate connection
          val uniqueFolderId = projectRoot
            .map { path =>
              this.folderIdMap
                .getOrElseUpdate(path, BloopServers.nextConnectionNo())
                .toString()
            }
            .getOrElse("")

          val socketPath = dir.resolve(s"$pid-$uniqueFolderId")
          if (Files.exists(socketPath))
            try Files.delete(socketPath)
            catch {
              case NonFatal(e) =>
                // This seems to be happening sometimes in tests
                scribe
                  .debug("Unexpected error while deleting the BSP socket", e)
            }
          BspConnectionAddress.UnixDomainSocket(socketPath.toFile)
        },
        bspStdout = bloopLogger.bloopBspStdout,
        bspStderr = bloopLogger.bloopBspStderr,
      )

    val additionalProperties = List(
      Properties
        .propOrNone("coursier.credentials")
        .map(value => s"-Dcoursier.credentials=$value")
    ).flatten
    userConfig.map(_.bloopJvmProperties.properties).flatten match {
      case Some(opts) if opts.nonEmpty =>
        config.copy(javaOpts = opts ++ additionalProperties)
      case _ => config.copy(javaOpts = config.javaOpts ++ additionalProperties)
    }
  }

}
