package tests

import java.nio.file.Files
import scala.meta.io.AbsolutePath
import scala.meta.internal.metals.MetalsEnrichments._

object CreateDirectoriesSuite extends BaseSuite {

  test("symlinks") {

    /**
     * Starting from this layout, create /.sbt/1.0/plugins/foo/bar/
     * and check that we end up with /plugins/foo/bar
     *
     *  /
     *  ├── plugins
     *  └── .sbt
     *      └── 1.0
     *          └── plugins -> ../../../plugins
     */
    val root = Files.createTempDirectory("scalameta")
    val plugins = Files.createDirectory(root.resolve("plugins"))
    val sbt1 = Files.createDirectories(root.resolve(".sbt").resolve("1.0"))
    Files.createSymbolicLink(sbt1.resolve("plugins"), plugins)
    // absolute path following symlink
    val symlinkFooBarPath = AbsolutePath(
      sbt1.toAbsolutePath.resolve("plugins").resolve("foo").resolve("bar")
    )
    // absolute path without symlink
    val directFooBarPath = AbsolutePath(plugins.resolve("foo").resolve("bar"))
    assert(!directFooBarPath.isDirectory)
    symlinkFooBarPath.createDirectories()
    assert(directFooBarPath.isDirectory)
  }

}
