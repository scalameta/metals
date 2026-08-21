package tests.mbt

import scala.collection.parallel.mutable.ParArray
import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.EmptyReportContext
import scala.meta.internal.metals.ReportContext
import scala.meta.internal.metals.mbt.ProtoOutlineTypes
import scala.meta.internal.metals.mbt.TurbineCompiler
import scala.meta.internal.metals.mbt.VirtualTextDocument
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.proto.codegen.java.JavaOutlineGenerator
import scala.meta.internal.proto.diag.{SourceFile => ProtoSourceFile}
import scala.meta.internal.proto.parse.{Parser => ProtoParser}
import scala.meta.pc
import scala.meta.pc.ProgressBars

import com.google.turbine.diag.SourceFile
import munit.Location
import tests.BaseSuite

/**
 * The types a `.proto` compiles to, by the two routes that have to agree.
 *
 * Deleting or changing a `.proto` hides its types from CLASS_PATH, and the
 * names for that come from indexing the outlines it generated. Turbine emits
 * one classfile per type in those outlines, so anything the indexing misses
 * stays resolvable after its message is gone.
 *
 * Each case asserts both routes against the same literal set: what the indexing
 * reports, and what Turbine actually emitted.
 */
class ProtoOutlineTypesSuite extends BaseSuite {

  private implicit val reportContext: ReportContext = EmptyReportContext

  checkDeclaredTypes(
    name = "a-nested-message-declares-types-under-its-outer",
    source = """|syntax = "proto3";
                |package com.example.api;
                |option java_package = "com.example.api.jproto";
                |option java_multiple_files = true;
                |message User {
                |  string name = 1;
                |  message Address {
                |    string city = 1;
                |  }
                |}
                |""".stripMargin,
    expected = Set(
      "com/example/api/jproto/User", "com/example/api/jproto/UserOrBuilder",
      "com/example/api/jproto/User$Builder",
      "com/example/api/jproto/User$Address",
      "com/example/api/jproto/User$AddressOrBuilder",
      "com/example/api/jproto/User$Address$Builder",
      "com/example/api/jproto/UserProfile",
    ),
  )

  checkDeclaredTypes(
    name = "two-toplevel-messages-in-one-file-both-declare-types",
    source = """|syntax = "proto3";
                |package com.example.api;
                |option java_package = "com.example.api.jproto";
                |option java_multiple_files = true;
                |message User {
                |  string name = 1;
                |}
                |message Account {
                |  string owner = 1;
                |}
                |""".stripMargin,
    expected = Set(
      "com/example/api/jproto/User", "com/example/api/jproto/UserOrBuilder",
      "com/example/api/jproto/User$Builder", "com/example/api/jproto/Account",
      "com/example/api/jproto/AccountOrBuilder",
      "com/example/api/jproto/Account$Builder",
      "com/example/api/jproto/UserProfile",
    ),
  )

  checkDeclaredTypes(
    name = "a-message-nested-two-deep-declares-types-at-every-level",
    source = """|syntax = "proto3";
                |package com.example.api;
                |option java_package = "com.example.api.jproto";
                |option java_multiple_files = true;
                |message Outer {
                |  message Middle {
                |    message Inner {
                |      string name = 1;
                |    }
                |  }
                |}
                |""".stripMargin,
    expected = Set(
      "com/example/api/jproto/Outer", "com/example/api/jproto/OuterOrBuilder",
      "com/example/api/jproto/Outer$Builder",
      "com/example/api/jproto/Outer$Middle",
      "com/example/api/jproto/Outer$MiddleOrBuilder",
      "com/example/api/jproto/Outer$Middle$Builder",
      "com/example/api/jproto/Outer$Middle$Inner",
      "com/example/api/jproto/Outer$Middle$InnerOrBuilder",
      "com/example/api/jproto/Outer$Middle$Inner$Builder",
      "com/example/api/jproto/UserProfile",
    ),
  )

  // Without java_multiple_files, which is the default, everything nests inside
  // the outer class named after the file.
  checkDeclaredTypes(
    name = "one-generated-file-nests-every-type-under-the-outer-class",
    source = """|syntax = "proto3";
                |package com.example.api;
                |option java_package = "com.example.api.jproto";
                |message User {
                |  string name = 1;
                |  message Address {
                |    string city = 1;
                |  }
                |}
                |""".stripMargin,
    expected = Set(
      "com/example/api/jproto/UserProfile",
      "com/example/api/jproto/UserProfile$User",
      "com/example/api/jproto/UserProfile$UserOrBuilder",
      "com/example/api/jproto/UserProfile$User$Builder",
      "com/example/api/jproto/UserProfile$User$Address",
      "com/example/api/jproto/UserProfile$User$AddressOrBuilder",
      "com/example/api/jproto/UserProfile$User$Address$Builder",
    ),
  )

  private def checkDeclaredTypes(
      name: String,
      source: String,
      expected: Set[String],
  )(implicit loc: Location): Unit =
    test(name) {
      val outlines = generateOutlines(source)

      assertEquals(
        ProtoOutlineTypes.declaredBy(outlines, new Mtags()).toSet,
        expected,
        "what the indexing reports",
      )
      assertEquals(
        compile(outlines),
        expected,
        "what Turbine emitted",
      )
    }

  /** Mirrors how MbtProtobufWorkspaceSymbolProvider wraps a generated file. */
  private def generateOutlines(source: String): Seq[VirtualTextDocument] = {
    val parsed =
      ProtoParser.parse(new ProtoSourceFile(protoFileName, source))
    // Empty, so the generated package is whatever java_package says.
    val javaPackagePrefix = ""
    val defaultOuterClassName = "UserProfile"
    val outputs =
      new JavaOutlineGenerator(javaPackagePrefix, defaultOuterClassName)
        .generate(parsed)
        .asScala
        .toSeq

    outputs.map { output =>
      val withoutExtension = output.path().stripSuffix(".java")
      val className = withoutExtension.split('/').last
      val outputPackage = withoutExtension.split('/').dropRight(1).mkString("/")
      val pkg = if (outputPackage.nonEmpty) outputPackage + "/" else ""
      VirtualTextDocument(
        java.net.URI.create("file:///" + output.path()),
        pc.Language.JAVA,
        output.content(),
        Seq(pkg),
        Seq(pkg + className + "#"),
      )
    }
  }

  private def compile(outlines: Seq[VirtualTextDocument]): Set[String] =
    TurbineCompiler
      .compileClassfiles[Seq[SourceFile]](
        toParse = ParArray(
          outlines.map(outline =>
            new SourceFile(outline.uri().getPath, outline.text)
          )
        ),
        toSourceFile = identity,
        classpath = Nil,
        progressBars = ProgressBars.EMPTY,
      )
      .lowered
      .bytes()
      .keySet()
      .asScala
      .toSet

  private val protoFileName = "user_profile.proto"
}
