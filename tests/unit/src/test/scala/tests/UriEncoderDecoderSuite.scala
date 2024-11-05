package tests

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

}
