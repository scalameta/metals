package tests

import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.util.zip.ZipOutputStream

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
    test("dont decode nor encode") {
      val tempFolder = Files
        .createTempDirectory("metals")
        .resolve("jdk-11.0.18%252B10/jdk-11.0.18+10")
      Files.createDirectories(tempFolder)
      // a path that contains a `%` that should not be encoded to `%25`, and a `+` that should not be decoded to ` `
      val zipFile = tempFolder.resolve("src.zip")
      // create empty zip file
      new ZipOutputStream(new FileOutputStream(zipFile.toFile)).close()
      val srcFile = "/jdk.zipfs/jdk/nio/zipfs/ZipFileStore.java"
      val srcUri = s"jar:${zipFile.toUri}!$srcFile"
      assertEquals(srcUri.toAbsolutePath.toString, srcFile)
    }

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

  if (isWindows) {
    test("encode-decode-at") {
      val uri =
        "jar:file%3A///C%3A/Users/niander/scoop/persist/coursier/cache/https/myproj%2540myorg.pkgs.visualstudio.com/myproj/_packaging/MyMavenFeed/maven/v1/com/azure/azure-security-keyvault-certificates/4.6.3/azure-security-keyvault-certificates-4.6.3-sources.jar%21/com/azure/security/keyvault/certificates/CertificateClient.java"
      try {
        uri.toAbsolutePath
      } catch {
        case e: NoSuchFileException => assertNotContains(e.getMessage(), "@")
      }
    }
  }

}
