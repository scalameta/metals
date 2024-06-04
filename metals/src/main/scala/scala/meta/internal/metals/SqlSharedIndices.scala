package scala.meta.internal.metals

import java.nio.file.Paths

import scala.meta.io.AbsolutePath

class SqlSharedIndices
    extends H2ConnectionProvider(
      () => AbsolutePath(Paths.get(sys.props("user.home"))).resolve(".metals"),
      "metals-shared",
      "/shared-db/migration",
    ) {

  val jvmTypeHierarchy: JarTypeHierarchy = new JarTypeHierarchy(() => connect)
}
