package tests.mbt

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.meta.internal.metals.mbt.ProtoGeneratedJavaFiles
import scala.meta.internal.metals.mbt.ProtoJavaVirtualFile
import scala.meta.internal.metals.mbt.VirtualTextDocument
import scala.meta.io.AbsolutePath
import scala.meta.pc

import munit.AnyFixture

class ProtoGeneratedJavaFilesSuite extends munit.FunSuite {
  val workspace = new tests.TemporaryDirectoryFixture()
  override def munitFixtures: Seq[AnyFixture[_]] = List(workspace)

  private val protoRelative = "a/src/main/proto/client.proto"
  private val className = "ClientProtos"
  private val content =
    """|package com.example.api.jproto;
       |public final class ClientProtos {}
       |""".stripMargin

  private def protoPath: AbsolutePath = workspace().resolve(protoRelative)

  private def outline(name: String, text: String): VirtualTextDocument =
    VirtualTextDocument(
      ProtoJavaVirtualFile.makeUri(protoPath, name),
      pc.Language.JAVA,
      text,
      Seq("com/example/api/jproto"),
      Nil,
    )

  private def readText(file: AbsolutePath): String =
    new String(Files.readAllBytes(file.toNIO), StandardCharsets.UTF_8)

  test("regenerates-when-missing") {
    val file = ProtoGeneratedJavaFiles
      .materialize(workspace(), protoPath, className, content)
      .getOrElse(fail("expected the outline to be materialized"))
    Files.delete(file.toNIO)
    assert(!Files.exists(file.toNIO))

    ProtoGeneratedJavaFiles.regenerateIfMissing(
      workspace(),
      file,
      protoPath,
      Seq(outline(className, content)),
    )

    assert(Files.exists(file.toNIO), "expected the outline to be regenerated")
    assertEquals(readText(file), content)
  }

  test("no-op-when-present") {
    val file = ProtoGeneratedJavaFiles
      .materialize(workspace(), protoPath, className, content)
      .getOrElse(fail("expected the outline to be materialized"))

    // The outlines thunk must not be evaluated when the file already exists.
    ProtoGeneratedJavaFiles.regenerateIfMissing(
      workspace(),
      file,
      protoPath,
      fail("outlines should not be evaluated when the file exists"),
    )

    assertEquals(readText(file), content)
  }

  test("matches-outline-by-class-name") {
    val file = ProtoGeneratedJavaFiles
      .materialize(workspace(), protoPath, className, content)
      .getOrElse(fail("expected the outline to be materialized"))
    Files.delete(file.toNIO)

    ProtoGeneratedJavaFiles.regenerateIfMissing(
      workspace(),
      file,
      protoPath,
      Seq(
        outline("Session", "package com.example.api.jproto; class Session {}"),
        outline(className, content),
      ),
    )

    assertEquals(readText(file), content)
  }
}
