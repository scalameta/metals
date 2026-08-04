package tests.mbt

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.mbt.ProtoGeneratedJavaFiles
import scala.meta.internal.metals.mbt.ProtoJavaVirtualFile
import scala.meta.internal.metals.mbt.VirtualTextDocument
import scala.meta.io.AbsolutePath
import scala.meta.pc

import munit.AnyFixture
import tests.MetalsTestEnrichments._

class ProtoGeneratedJavaFilesSuite extends munit.FunSuite {
  val workspace = new tests.TemporaryDirectoryFixture()
  override def munitFixtures: Seq[AnyFixture[_]] = List(workspace)

  private val protoRelative = "a/src/main/proto/client.proto"
  private val className = "ClientProtos"
  private val content =
    """|package com.example.api.jproto;
       |public final class ClientProtos {}""".stripMargin

  private def protoPath: AbsolutePath = workspace().resolve(protoRelative)

  private def outline(name: String, text: String): VirtualTextDocument =
    VirtualTextDocument(
      ProtoJavaVirtualFile.makeUri(protoPath, name),
      pc.Language.JAVA,
      text,
      Seq("com/example/api/jproto"),
      Nil,
    )

  test("regenerates-when-missing") {
    val file = ProtoGeneratedJavaFiles
      .materialize(workspace(), protoPath, className, content)
      .getOrElse(fail("expected the outline to be materialized"))
    file.delete()

    ProtoGeneratedJavaFiles.regenerateIfMissing(
      workspace(),
      file,
      protoPath,
      Seq(outline(className, content)),
    )

    assert(file.exists, "expected the outline to be regenerated")
    assertEquals(file.text, content)
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

    assertEquals(file.text, content)
  }

  test("matches-outline-by-class-name") {
    val file = ProtoGeneratedJavaFiles
      .materialize(workspace(), protoPath, className, content)
      .getOrElse(fail("expected the outline to be materialized"))
    file.delete()

    ProtoGeneratedJavaFiles.regenerateIfMissing(
      workspace(),
      file,
      protoPath,
      Seq(
        outline("Session", "package com.example.api.jproto; class Session {}"),
        outline(className, content),
      ),
    )

    assertEquals(file.text, content)
  }
}
