package scala.meta.internal.metals

import java.nio.file.Paths

import scala.meta.io.AbsolutePath

class SqlSharedIndices
    extends H2ConnectionProvider(
      directory =
        AbsolutePath(Paths.get(sys.props("user.home"))).resolve(".metals"),
      name = "metals-shared",
      migrations = "/shared-db/migration",
    ) {

  val jvmTypeHierarchy: JarTypeHierarchy = new JarTypeHierarchy(() => connect())
}
