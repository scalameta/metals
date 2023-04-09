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

  // windows doesn't %20 as escapes
  if (!isWindows) {
    test("dont-decode-uri") {
      val uri =
        "file:///Users/happyMetalsUser/hello%20space%20world/src/main/scala/Main.scala"
      val path = uri.toAbsolutePath
      assert(path.toString().contains("hello space world"))
    }

    test("encode-uri-space") {
      val uri =
        "file:///Users/happyMetalsUser/hello space+world/src/main/scala/Main.scala"
      val path = uri.toAbsolutePath
      assert(path.toString().contains("hello space+world"))
    }
  }

}
