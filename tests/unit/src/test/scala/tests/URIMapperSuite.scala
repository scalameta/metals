package tests

import scala.meta.internal.metals.JdkSources
import scala.meta.internal.metals.URIMapper

class URIMapperSuite extends BaseSuite {

  test("convertToMetalsFS-file-windows-1") {
    val entry = "file:///e%3A/Foo.scala"
    val expected = "file:///e:/Foo.scala"

    val uriMapper = new URIMapper()
    val obtained = uriMapper.convertToMetalsFS(entry)
    assertEquals(obtained, expected)
  }

  test("convertToMetalsFS-file-windows-2") {
    val entry = "file:///e:/Foo.scala"
    val expected = "file:///e:/Foo.scala"

    val uriMapper = new URIMapper()
    val obtained = uriMapper.convertToMetalsFS(entry)
    assertEquals(obtained, expected)
  }

  test("convertToMetalsFS-file-unix") {
    val entry = "file:///Foo.scala"
    val expected = "file:///Foo.scala"

    val uriMapper = new URIMapper()
    val obtained = uriMapper.convertToMetalsFS(entry)
    assertEquals(obtained, expected)
  }

  test("convertToMetalsFS-jdk") {
    val uriMapper = new URIMapper()
    val zip = JdkSources(None) match {
      case Right(zip) => zip
      case Left(_) => fail("No JDK defined")
    }
    val jdkUri = zip.toNIO.toUri().toString
    uriMapper.addJDK(zip)
    val metalsFSUri = uriMapper.convertToMetalsFS(jdkUri)
    val localFSUri = uriMapper.convertToLocal(metalsFSUri)
    assertEquals(localFSUri, jdkUri)
    val backToMetalsFSUri = uriMapper.convertToMetalsFS(localFSUri)
    assertEquals(backToMetalsFSUri, metalsFSUri)
  }

  test("convertToMetalsFS-jdk-class") {
    val uriMapper = new URIMapper()
    val zip = JdkSources(None) match {
      case Right(zip) => zip
      case Left(_) => fail("No JDK defined")
    }
    val jdkUri = zip.toNIO.toUri().toString
    uriMapper.addJDK(zip)
    val metalsFSUri = uriMapper.convertToMetalsFS(jdkUri)
    val metalsClassUri = s"$metalsFSUri/javaClass.ext"
    val localFSUri = uriMapper.convertToLocal(metalsClassUri)
    assertEquals(localFSUri, s"jar:$jdkUri!/javaClass.ext")
    val backToMetalsFSUri = uriMapper.convertToMetalsFS(localFSUri)
    assertEquals(backToMetalsFSUri, metalsClassUri)
  }
}
