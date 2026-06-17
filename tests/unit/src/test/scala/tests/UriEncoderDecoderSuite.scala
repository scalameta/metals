package tests

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import scala.meta.internal.metals.MetalsEnrichments.XtensionString
import scala.meta.internal.mtags.URIEncoderDecoder

class UriEncoderDecoderSuite extends BaseSuite {

  test("encode") {
    val entry =
      "metalsDecode:file:///home/user/metals/tests/slow/target/e2e/fileDecoderProvider/buildtarget/a[2.13.8].metals-buildtarget"
    val expected =
      "metalsDecode:file:///home/user/metals/tests/slow/target/e2e/fileDecoderProvider/buildtarget/a%5b2.13.8%5d.metals-buildtarget"

    val obtained = URIEncoderDecoder.encode(entry)
    assertEquals(
      obtained,
      expected,
    )
  }

  test("decode") {
    val entry =
      "metalsDecode:file:///home/user/metals/tests/slow/target/e2e/fileDecoderProvider/buildtarget/a%5b2.13.8%5d.metals-buildtarget"
    val expected =
      "metalsDecode:file:///home/user/metals/tests/slow/target/e2e/fileDecoderProvider/buildtarget/a[2.13.8].metals-buildtarget"
    val obtained = URIEncoderDecoder.decode(entry)
    assertEquals(
      obtained,
      expected,
    )
  }

  test("decode-uppercase") {
    val entry =
      "file:///home/user/metals/tests/slow/target/e2e/fileDecoderProvider/buildtarget/a%5B2.13.8%5D/"
    val expected =
      "file:///home/user/metals/tests/slow/target/e2e/fileDecoderProvider/buildtarget/a[2.13.8]/"
    val obtained = URIEncoderDecoder.decode(entry)
    assertEquals(
      obtained,
      expected,
    )
  }

  test("absolute-path-space") {
    // Should be NoSuchFileException instead of URI creation one
    intercept[java.nio.file.NoSuchFileException] {
      "jar:file:///C:/Program Files/Java/jdk-21.0.3+9/lib/src.zip!/java.base/java/lang/String.java".toAbsolutePath
    }
  }

  test("double-encoded-jar-uri") {
    // Regression for #3266: when stepping into a JDK source while debugging on
    // an old Windows JDK, scala-debug-adapter returns a stack-frame source URI
    // for src.zip that is double URL-encoded (%2520 == encoded %20 == encoded
    // space in e.g. "Program Files"). Metals used to fail with
    // FileSystemNotFoundException while creating the jar file system.
    //
    // Build a real src.zip under a directory whose name contains both a space
    // and a '+' (as in a real JDK path like "jdk-21.0.3+9"), then double-encode
    // the jar URI exactly as the buggy adapter does, and assert Metals resolves
    // the *actual* entry (the user story: navigate into the JDK source), not
    // merely that parsing no longer throws. The '+' guards against form-style
    // decoding that would turn it into a space.
    val dir =
      Files.createTempDirectory("metals-3266").resolve("Program Files+1")
    Files.createDirectories(dir)
    val zip = dir.resolve("src.zip")
    val entry = "java.base/java/io/PrintStream.java"
    val content = "package java.io\nclass PrintStream\n"

    val out = new ZipOutputStream(Files.newOutputStream(zip))
    try {
      out.putNextEntry(new ZipEntry(entry))
      out.write(content.getBytes(StandardCharsets.UTF_8))
      out.closeEntry()
    } finally out.close()

    // zip.toUri encodes the space as %20; replacing % with %25 reproduces the
    // double encoding (%2520) seen in the issue.
    val doubleEncoded =
      "jar:" + zip.toUri.toString.replace("%", "%25") + "!/" + entry

    val resolved = doubleEncoded.toAbsolutePath
    val nio = resolved.toNIO
    try {
      assert(
        Files.exists(nio),
        s"expected the double-encoded jar entry to resolve, got $resolved",
      )
      assertEquals(nio.getFileName.toString, "PrintStream.java")
      assertNoDiff(
        new String(Files.readAllBytes(nio), StandardCharsets.UTF_8),
        content,
      )
    } finally {
      // The resolver leaves the jar file system open on purpose; close ours so
      // the temp files can be cleaned up.
      nio.getFileSystem.close()
      Files.deleteIfExists(zip)
      Files.deleteIfExists(dir)
    }
  }

}
