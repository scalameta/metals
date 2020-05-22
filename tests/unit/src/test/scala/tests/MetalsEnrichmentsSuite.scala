package tests

import java.nio.file.Files

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath
import scala.meta.io.RelativePath

class MetalsEnrichmentsSuite extends BaseSuite {

  test("create-directories") {
    val tmpDir = Files.createTempDirectory("metals-enrichment")
    val absoluteTmpPath = AbsolutePath(tmpDir)
    val nothingToCreate: Seq[AbsolutePath] =
      absoluteTmpPath.createAndGetDirectories()
    assertEquals(nothingToCreate, Nil, "workspace is already created")

    val created = absoluteTmpPath.resolve("a/b/c").createAndGetDirectories()
    val relativeCreated =
      created.map(_.toRelative(absoluteTmpPath)).sortBy(_.toNIO)
    val expected = Seq("a", "a/b", "a/b/c").map(RelativePath.apply)
    assertEquals(relativeCreated, expected)
  }
}
