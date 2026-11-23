package tests

import java.nio.file.FileSystems
import java.nio.file.Paths

import scala.meta.internal.jdk.CollectionConverters.*
import scala.meta.internal.metals.config.GlobSyntaxConfig
import scala.meta.io.AbsolutePath

class GlobSyntaxConfigSuite extends munit.FunSuite {
  test("kotlin-files-are-watched") {
    val workspace = AbsolutePath(Paths.get("/workspace"))
    val globs = GlobSyntaxConfig.path
      .registrationOptions(workspace)
      .getWatchers
      .asScala
      .map(_.getGlobPattern.getLeft)
    val file = Paths.get("/workspace/a/src/main/kotlin/a/Account.kt")
    assert(
      clue(globs).exists(glob =>
        FileSystems.getDefault.getPathMatcher(s"glob:$glob").matches(file)
      )
    )
  }
}
