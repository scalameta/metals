package scala.meta.internal.metals

import java.nio.file.Paths

import scala.meta.io.AbsolutePath

import dev.dirs.ProjectDirectories

object MetalsDirectories {
  private val projectDirectories = ProjectDirectories.from(null, null, "metals")

  def getMetalsDirectory: AbsolutePath = {
    AbsolutePath(Paths.get(projectDirectories.dataDir))
  }
}

class SqlSharedIndices
    extends H2ConnectionProvider(
      directory = MetalsDirectories.getMetalsDirectory,
      name = "metals-shared",
      migrations = "/shared-db/migration",
    ) {

  val jvmTypeHierarchy: JarTypeHierarchy = new JarTypeHierarchy(() => connect)
}
