package scala.meta.internal.metals

import java.nio.file.Paths

import scala.util.Properties

import scala.meta.io.AbsolutePath

object MetalsDirectories {
  def getMetalsDirectory: AbsolutePath = {
    if (Properties.isLinux) {
      Option(System.getenv("XDG_DATA_HOME"))
        .map(xdg => Paths.get(xdg, "metals"))
        .getOrElse(Paths.get(sys.props("user.home"), ".local", "share", "metals"))
    } else {
      Paths.get(sys.props("user.home"), ".metals")
    }
  }.toAbsolutePath
}

class SqlSharedIndices
    extends H2ConnectionProvider(
      directory = MetalsDirectories.getMetalsDirectory,
      name = "metals-shared",
      migrations = "/shared-db/migration",
    ) {

  val jvmTypeHierarchy: JarTypeHierarchy = new JarTypeHierarchy(() => connect)
}
