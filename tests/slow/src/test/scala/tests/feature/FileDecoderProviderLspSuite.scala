package tests.feature

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.{BuildInfo => V}

import munit.TestOptions
import tests.BaseLspSuite

class FileDecoderProviderLspSuite extends BaseLspSuite("fileDecoderProvider") {

  check(
    "tasty-single",
    s"""|/metals.json
        |{
        |  "app": {
        |    "scalaVersion": "${V.scala3}"
        |  }
        |}
        |/app/src/main/scala/Main.scala
        |package foo.bar.example
        |object Main
        |""".stripMargin,
    "app/src/main/scala/Main.scala",
    None,
    "tasty-decoded",
    Right(FileDecoderProviderLspSuite.tastySingle)
  )

  check(
    "decode-jar",
    s"""
       |/metals.json
       |{
       |  "a": {
       |    "scalaVersion": "${scala.meta.internal.metals.BuildInfo.scala213}",
       |    "libraryDependencies": [
       |      "ch.epfl.scala:com-microsoft-java-debug-core:0.21.0+1-7f1080f1"
       |    ]
       |  }
       |}
       |/a/src/main/scala/a/Main.scala
       |package a
       |import com.microsoft.java.debug.core.protocol.Events._
       |
       |object Main {
       |  val a : ExitedEvent = null
       |  println(a)
       |}
       |""".stripMargin,
    "a/src/main/scala/a/Main.scala",
    None,
    "file-decode",
    Right(FileDecoderProviderLspSuite.EventsJarFile),
    customUri = Some(
      s"jar:${coursierCacheDir.toUri}/v1/https/repo1.maven.org/maven2/ch/epfl/scala/com-microsoft-java-debug-core/0.21.0%2B1-7f1080f1/com-microsoft-java-debug-core-0.21.0%2B1-7f1080f1-sources.jar!/com/microsoft/java/debug/core/protocol/Events.java"
    )
  )

  check(
    "tasty-multiple",
    s"""|/metals.json
        |{
        |  "app": {
        |    "scalaVersion": "${V.scala3}"
        |  }
        |}
        |/app/src/main/scala/Main.scala
        |package foo.bar.example
        |class Foo
        |class Bar
        |""".stripMargin,
    "app/src/main/scala/Main.scala",
    Some("foo/bar/example/Foo.tasty"),
    "tasty-decoded",
    Right(FileDecoderProviderLspSuite.tastyMultiple)
  )

  check(
    "tasty-toplevel",
    s"""|/metals.json
        |{
        |  "app": {
        |    "scalaVersion": "${V.scala3}"
        |  }
        |}
        |/app/src/main/scala/Main.scala
        |package foo.bar.example
        |object Main
        |def foo(): Unit = ()
        |""".stripMargin,
    "app/src/main/scala/Main.scala",
    Some("foo/bar/example/Main$package.tasty"),
    "tasty-decoded",
    Right(FileDecoderProviderLspSuite.tastyToplevel)
  )

  check(
    "cfr",
    s"""|/metals.json
        |{
        |  "app": {
        |    "scalaVersion": "${V.scala3}"
        |  }
        |}
        |/app/src/main/scala/Main.scala
        |package foo.bar.example
        |class Foo
        |class Bar
        |""".stripMargin,
    "app/src/main/scala/Main.scala",
    Some("foo/bar/example/Foo.class"),
    "cfr",
    Right(FileDecoderProviderLspSuite.cfr),
    str =>
      str
        .replaceAll(
          ".*(Decompiled with CFR )(\\d|.)*\\.",
          " * Decompiled with CFR VERSION."
        )
  )

  check(
    "cfr-java",
    s"""|/metals.json
        |{
        |  "app": {
        |    "scalaVersion": "${V.scala3}"
        |  }
        |}
        |/app/src/main/java/foo/bar/example/Main.java
        |package foo.bar.example;
        |class Main {}
        |""".stripMargin,
    "app/src/main/java/foo/bar/example/Main.java",
    None,
    "cfr",
    Right(FileDecoderProviderLspSuite.cfrJava),
    str =>
      str
        .replaceAll(
          ".*(Decompiled with CFR )(\\d|.)*\\.",
          " * Decompiled with CFR VERSION."
        )
  )

  check(
    "cfr-missing",
    s"""|/metals.json
        |{
        |  "app": {
        |    "scalaVersion": "${V.scala3}"
        |  }
        |}
        |/app/src/main/java/foo/bar/example/Main.java
        |package foo.bar.example.not.here;
        |class Main {}
        |""".stripMargin,
    "app/src/main/java/foo/bar/example/Main.java",
    None,
    "cfr",
    Left(FileDecoderProviderLspSuite.cfrMissing),
    str =>
      str
        .replace("\\", "/")
        .replaceAll(
          "(No such file ).*(\\/foo\\/bar\\/example\\/Main\\.class)",
          "$1$2"
        )
        .replaceAll(
          "(CannotLoadClassException: ).*(\\/foo\\/bar\\/example\\/Main\\.class )",
          "$1$2"
        )
  )

  check(
    "cfr-toplevel",
    s"""|/metals.json
        |{
        |  "app": {
        |    "scalaVersion": "${V.scala3}"
        |  }
        |}
        |/app/src/main/scala/Main.scala
        |package foo.bar.example
        |class Foo
        |class Bar
        |def foo(): Unit = ()
        |""".stripMargin,
    "app/src/main/scala/Main.scala",
    Some("foo/bar/example/Main$package.class"),
    "cfr",
    Right(FileDecoderProviderLspSuite.cfrToplevel),
    str =>
      str
        .replaceAll(
          ".*(Decompiled with CFR )(\\d|.)*\\.",
          " * Decompiled with CFR VERSION."
        )
  )

  check(
    "javap-java",
    s"""|/metals.json
        |{
        |  "app": {
        |    "scalaVersion": "${V.scala3}"
        |  }
        |}
        |/app/src/main/java/foo/bar/example/Main.java
        |package foo.bar.example;
        |class Main {}
        |""".stripMargin,
    "app/src/main/java/foo/bar/example/Main.java",
    None,
    "javap",
    Right(FileDecoderProviderLspSuite.javapJava)
  )

  check(
    "javap-missing",
    s"""|/metals.json
        |{
        |  "app": {
        |    "scalaVersion": "${V.scala3}"
        |  }
        |}
        |/app/src/main/java/foo/bar/example/Main.java
        |package foo.bar.example.not.here;
        |class Main {}
        |""".stripMargin,
    "app/src/main/java/foo/bar/example/Main.java",
    None,
    "javap",
    Left(FileDecoderProviderLspSuite.javapMissing)
  )

  check(
    "javap",
    s"""|/metals.json
        |{
        |  "app": {
        |    "scalaVersion": "${V.scala3}"
        |  }
        |}
        |/app/src/main/scala/Main.scala
        |package foo.bar.example
        |class Foo {
        |  private final def foo: Int = 42
        |}
        |class Bar
        |""".stripMargin,
    "app/src/main/scala/Main.scala",
    Some("foo/bar/example/Foo.class"),
    "javap",
    Right(FileDecoderProviderLspSuite.javap)
  )

  check(
    "javap-toplevel",
    s"""|/metals.json
        |{
        |  "app": {
        |    "scalaVersion": "${V.scala3}"
        |  }
        |}
        |/app/src/main/scala/Main.scala
        |package foo.bar.example
        |class Foo
        |class Bar
        |def foo(): Unit = ()
        |""".stripMargin,
    "app/src/main/scala/Main.scala",
    Some("foo/bar/example/Main$package.class"),
    "javap",
    Right(FileDecoderProviderLspSuite.javapToplevel)
  )

  check(
    "javap-verbose",
    s"""|/metals.json
        |{
        |  "app": {
        |    "scalaVersion": "${V.scala3}"
        |  }
        |}
        |/app/src/main/scala/Main.scala
        |package foo.bar.example
        |class Foo {
        |  private final def foo: Int = 42
        |}
        |class Bar
        |""".stripMargin,
    "app/src/main/scala/Main.scala",
    Some("foo/bar/example/Foo.class"),
    "javap-verbose",
    Right(FileDecoderProviderLspSuite.javapVerbose),
    str => str.substring(str.indexOf("Compiled from"), str.length())
  )

  check(
    "semanticdb-compact",
    s"""|/metals.json
        |{
        |  "app": {
        |    "scalaVersion": "${V.scala3}"
        |  }
        |}
        |/app/src/main/scala/Main.scala
        |package foo.bar.example
        |class Foo
        |class Bar
        |""".stripMargin,
    "app/src/main/scala/Main.scala",
    None,
    "semanticdb-compact",
    Right(FileDecoderProviderLspSuite.semanticdbCompact)
  )

  check(
    "semanticdb-detailed",
    s"""|/metals.json
        |{
        |  "app": {
        |    "scalaVersion": "${V.scala3}"
        |  }
        |}
        |/app/src/main/scala/Main.scala
        |package foo.bar.example
        |class Foo
        |class Bar {
        |  def foo(): Unit = ()
        |}
        |""".stripMargin,
    "app/src/main/scala/Main.scala",
    None,
    "semanticdb-detailed",
    Right(FileDecoderProviderLspSuite.semanticdbDetailed)
  )

  def check(
      testName: TestOptions,
      input: String,
      filePath: String,
      picked: Option[String],
      extension: String,
      expected: Either[String, String],
      transformResult: String => String = identity,
      customUri: Option[String] = None
  ): Unit = {
    test(testName) {
      picked.foreach { pickedItem =>
        client.showMessageRequestHandler = { params =>
          params.getActions().asScala.find(_.getTitle == pickedItem)
        }
      }
      for {
        _ <- initialize(input)
        _ <- server.didOpen(filePath)
        result <- server.executeDecodeFileCommand(
          customUri.getOrElse(
            s"metalsDecode:file://$workspace/$filePath.$extension"
          )
        )
      } yield {
        assertEquals(
          if (result.value != null) Right(transformResult(result.value))
          else Left(transformResult(result.error)),
          expected
        )
      }
    }
  }
}

object FileDecoderProviderLspSuite {
  private val tastySingle =
    s"""|Names:
        |   0: ASTs
        |   1: foo
        |   2: bar
        |   3: foo[Qualified . bar]
        |   4: example
        |   5: foo[Qualified . bar][Qualified . example]
        |   6: Main
        |   7: Main[ModuleClass]
        |   8: <init>
        |   9: foo[Qualified . bar][Qualified . example][Qualified . Main]
        |  10: foo[Qualified . bar][Qualified . example][Qualified . Main][ModuleClass]
        |  11: <init>[Signed Signature(List(),foo.bar.example.Main$$) @<init>]
        |  12: java
        |  13: lang
        |  14: java[Qualified . lang]
        |  15: Object
        |  16: java[Qualified . lang][Qualified . Object]
        |  17: <init>[Signed Signature(List(),java.lang.Object) @<init>]
        |  18: _
        |  19: Unit
        |  20: scala
        |  21: writeReplace
        |  22: AnyRef
        |  23: runtime
        |  24: scala[Qualified . runtime]
        |  25: ModuleSerializationProxy
        |  26: scala[Qualified . runtime][Qualified . ModuleSerializationProxy]
        |  27: Class
        |  28: java[Qualified . lang][Qualified . Class]
        |  29: <init>[Signed Signature(List(java.lang.Class),scala.runtime.ModuleSerializationProxy) @<init>]
        |  30: SourceFile
        |  31: annotation
        |  32: scala[Qualified . annotation]
        |  33: internal
        |  34: scala[Qualified . annotation][Qualified . internal]
        |  35: scala[Qualified . annotation][Qualified . internal][Qualified . SourceFile]
        |  36: String
        |  37: java[Qualified . lang][Qualified . String]
        |  38: <init>[Signed Signature(List(java.lang.String),scala.annotation.internal.SourceFile) @<init>]
        |  39: app/src/main/scala/Main.scala
        |  40: Positions
        |  41: Comments
        |
        |Trees:
        |start = Addr(0), base = 344, current = Addr(0), end = Addr(101)
        |101 bytes of AST, base = Addr(0)
        |
        |     0: PACKAGE(99)
        |     2:   TERMREFpkg 5 [foo[Qualified . bar][Qualified . example]]
        |     4:   VALDEF(18) 6 [Main]
        |     7:     IDENTtpt 7 [Main[ModuleClass]]
        |     9:       TYPEREFsymbol 24
        |    11:         TERMREFpkg 5 [foo[Qualified . bar][Qualified . example]]
        |    13:     APPLY(8)
        |    15:       SELECTin(6) 11 [<init>[Signed Signature(List(),foo.bar.example.Main$$) @<init>]]
        |    18:         NEW
        |    19:           SHAREDterm 7
        |    21:         SHAREDtype 9
        |    23:     OBJECT
        |    24:   TYPEDEF(75) 7 [Main[ModuleClass]]
        |    27:     TEMPLATE(53)
        |    29:       APPLY(10)
        |    31:         SELECTin(8) 17 [<init>[Signed Signature(List(),java.lang.Object) @<init>]]
        |    34:           NEW
        |    35:             TYPEREF 15 [Object]
        |    37:               TERMREFpkg 14 [java[Qualified . lang]]
        |    39:           SHAREDtype 35
        |    41:       SELFDEF 18 [_]
        |    43:         SINGLETONtpt
        |    44:           TERMREFsymbol 4
        |    46:             SHAREDtype 11
        |    48:       DEFDEF(7) 8 [<init>]
        |    51:         EMPTYCLAUSE
        |    52:         TYPEREF 19 [Unit]
        |    54:           TERMREFpkg 20 [scala]
        |    56:         STABLE
        |    57:       DEFDEF(23) 21 [writeReplace]
        |    60:         EMPTYCLAUSE
        |    61:         TYPEREF 22 [AnyRef]
        |    63:           SHAREDtype 54
        |    65:         APPLY(13)
        |    67:           SELECTin(8) 29 [<init>[Signed Signature(List(java.lang.Class),scala.runtime.ModuleSerializationProxy) @<init>]]
        |    70:             NEW
        |    71:               TYPEREF 25 [ModuleSerializationProxy]
        |    73:                 TERMREFpkg 24 [scala[Qualified . runtime]]
        |    75:             SHAREDtype 71
        |    77:           CLASSconst
        |    78:             SHAREDtype 44
        |    80:         PRIVATE
        |    81:         SYNTHETIC
        |    82:     OBJECT
        |    83:     ANNOTATION(16)
        |    85:       TYPEREF 30 [SourceFile]
        |    87:         TERMREFpkg 34 [scala[Qualified . annotation][Qualified . internal]]
        |    89:       APPLY(10)
        |    91:         SELECTin(6) 38 [<init>[Signed Signature(List(java.lang.String),scala.annotation.internal.SourceFile) @<init>]]
        |    94:           NEW
        |    95:             SHAREDtype 85
        |    97:           SHAREDtype 85
        |    99:         STRINGconst 39 [app/src/main/scala/Main.scala]
        |   101:
        |
        | 38 position bytes:
        |   lines: 3
        |   line sizes: 23, 11, 0
        |   positions:
        |         0: 0 .. 35
        |         4: 24 .. 24
        |         7: 24 .. 24
        |        24: 24 .. 35
        |        27: 24 .. 24
        |        35: 31 .. 31
        |        44: 24 .. 24
        |        48: 24 .. 24
        |        52: 24 .. 24
        |        57: 31 .. 31
        |        61: 31 .. 31
        |        71: 31 .. 31
        |        77: 31 .. 31
        |
        | source paths:
        |         0: app/src/main/scala/Main.scala
        |
        |
        | 0 comment bytes:
        |""".stripMargin

  private val tastyMultiple =
    s"""|Names:
        |   0: ASTs
        |   1: foo
        |   2: bar
        |   3: foo[Qualified . bar]
        |   4: example
        |   5: foo[Qualified . bar][Qualified . example]
        |   6: Foo
        |   7: <init>
        |   8: java
        |   9: lang
        |  10: java[Qualified . lang]
        |  11: Object
        |  12: java[Qualified . lang][Qualified . Object]
        |  13: <init>[Signed Signature(List(),java.lang.Object) @<init>]
        |  14: Unit
        |  15: scala
        |  16: SourceFile
        |  17: annotation
        |  18: scala[Qualified . annotation]
        |  19: internal
        |  20: scala[Qualified . annotation][Qualified . internal]
        |  21: scala[Qualified . annotation][Qualified . internal][Qualified . SourceFile]
        |  22: String
        |  23: java[Qualified . lang][Qualified . String]
        |  24: <init>[Signed Signature(List(java.lang.String),scala.annotation.internal.SourceFile) @<init>]
        |  25: app/src/main/scala/Main.scala
        |  26: Positions
        |  27: Comments
        |
        |Trees:
        |start = Addr(0), base = 245, current = Addr(0), end = Addr(48)
        |48 bytes of AST, base = Addr(0)
        |
        |     0: PACKAGE(46)
        |     2:   TERMREFpkg 5 [foo[Qualified . bar][Qualified . example]]
        |     4:   TYPEDEF(42) 6 [Foo]
        |     7:     TEMPLATE(21)
        |     9:       APPLY(10)
        |    11:         SELECTin(8) 13 [<init>[Signed Signature(List(),java.lang.Object) @<init>]]
        |    14:           NEW
        |    15:             TYPEREF 11 [Object]
        |    17:               TERMREFpkg 10 [java[Qualified . lang]]
        |    19:           SHAREDtype 15
        |    21:       DEFDEF(7) 7 [<init>]
        |    24:         EMPTYCLAUSE
        |    25:         TYPEREF 14 [Unit]
        |    27:           TERMREFpkg 15 [scala]
        |    29:         STABLE
        |    30:     ANNOTATION(16)
        |    32:       TYPEREF 16 [SourceFile]
        |    34:         TERMREFpkg 20 [scala[Qualified . annotation][Qualified . internal]]
        |    36:       APPLY(10)
        |    38:         SELECTin(6) 24 [<init>[Signed Signature(List(java.lang.String),scala.annotation.internal.SourceFile) @<init>]]
        |    41:           NEW
        |    42:             SHAREDtype 32
        |    44:           SHAREDtype 32
        |    46:         STRINGconst 25 [app/src/main/scala/Main.scala]
        |    48:
        |
        | 23 position bytes:
        |   lines: 4
        |   line sizes: 23, 9, 9, 0
        |   positions:
        |         0: 0 .. 43
        |         4: 24 .. 33
        |         7: 24 .. 24
        |        15: 30 .. 30
        |        21: 24 .. 24
        |        25: 24 .. 24
        |
        | source paths:
        |         0: app/src/main/scala/Main.scala
        |
        |
        | 0 comment bytes:
        |""".stripMargin

  private val tastyToplevel =
    s"""|Names:
        |   0: ASTs
        |   1: foo
        |   2: bar
        |   3: foo[Qualified . bar]
        |   4: example
        |   5: foo[Qualified . bar][Qualified . example]
        |   6: Main$$package
        |   7: Main$$package[ModuleClass]
        |   8: <init>
        |   9: foo[Qualified . bar][Qualified . example][Qualified . Main$$package]
        |  10: foo[Qualified . bar][Qualified . example][Qualified . Main$$package][ModuleClass]
        |  11: <init>[Signed Signature(List(),foo.bar.example.Main$$package$$) @<init>]
        |  12: java
        |  13: lang
        |  14: java[Qualified . lang]
        |  15: Object
        |  16: java[Qualified . lang][Qualified . Object]
        |  17: <init>[Signed Signature(List(),java.lang.Object) @<init>]
        |  18: _
        |  19: Unit
        |  20: scala
        |  21: writeReplace
        |  22: AnyRef
        |  23: runtime
        |  24: scala[Qualified . runtime]
        |  25: ModuleSerializationProxy
        |  26: scala[Qualified . runtime][Qualified . ModuleSerializationProxy]
        |  27: Class
        |  28: java[Qualified . lang][Qualified . Class]
        |  29: <init>[Signed Signature(List(java.lang.Class),scala.runtime.ModuleSerializationProxy) @<init>]
        |  30: SourceFile
        |  31: annotation
        |  32: scala[Qualified . annotation]
        |  33: internal
        |  34: scala[Qualified . annotation][Qualified . internal]
        |  35: scala[Qualified . annotation][Qualified . internal][Qualified . SourceFile]
        |  36: String
        |  37: java[Qualified . lang][Qualified . String]
        |  38: <init>[Signed Signature(List(java.lang.String),scala.annotation.internal.SourceFile) @<init>]
        |  39: app/src/main/scala/Main.scala
        |  40: Positions
        |  41: Comments
        |
        |Trees:
        |start = Addr(0), base = 352, current = Addr(0), end = Addr(114)
        |114 bytes of AST, base = Addr(0)
        |
        |     0: PACKAGE(112)
        |     2:   TERMREFpkg 5 [foo[Qualified . bar][Qualified . example]]
        |     4:   VALDEF(19) 6 [Main$$package]
        |     7:     IDENTtpt 7 [Main$$package[ModuleClass]]
        |     9:       TYPEREFsymbol 25
        |    11:         TERMREFpkg 5 [foo[Qualified . bar][Qualified . example]]
        |    13:     APPLY(8)
        |    15:       SELECTin(6) 11 [<init>[Signed Signature(List(),foo.bar.example.Main$$package$$) @<init>]]
        |    18:         NEW
        |    19:           SHAREDterm 7
        |    21:         SHAREDtype 9
        |    23:     OBJECT
        |    24:     SYNTHETIC
        |    25:   TYPEDEF(87) 7 [Main$$package[ModuleClass]]
        |    28:     TEMPLATE(64)
        |    30:       APPLY(10)
        |    32:         SELECTin(8) 17 [<init>[Signed Signature(List(),java.lang.Object) @<init>]]
        |    35:           NEW
        |    36:             TYPEREF 15 [Object]
        |    38:               TERMREFpkg 14 [java[Qualified . lang]]
        |    40:           SHAREDtype 36
        |    42:       SELFDEF 18 [_]
        |    44:         SINGLETONtpt
        |    45:           TERMREFsymbol 4
        |    47:             SHAREDtype 11
        |    49:       DEFDEF(7) 8 [<init>]
        |    52:         EMPTYCLAUSE
        |    53:         TYPEREF 19 [Unit]
        |    55:           TERMREFpkg 20 [scala]
        |    57:         STABLE
        |    58:       DEFDEF(23) 21 [writeReplace]
        |    61:         EMPTYCLAUSE
        |    62:         TYPEREF 22 [AnyRef]
        |    64:           SHAREDtype 55
        |    66:         APPLY(13)
        |    68:           SELECTin(8) 29 [<init>[Signed Signature(List(java.lang.Class),scala.runtime.ModuleSerializationProxy) @<init>]]
        |    71:             NEW
        |    72:               TYPEREF 25 [ModuleSerializationProxy]
        |    74:                 TERMREFpkg 24 [scala[Qualified . runtime]]
        |    76:             SHAREDtype 72
        |    78:           CLASSconst
        |    79:             SHAREDtype 45
        |    81:         PRIVATE
        |    82:         SYNTHETIC
        |    83:       DEFDEF(9) 1 [foo]
        |    86:         EMPTYCLAUSE
        |    87:         IDENTtpt 19 [Unit]
        |    89:           TYPEREF 19 [Unit]
        |    91:             TERMREFpkg 20 [scala]
        |    93:         UNITconst
        |    94:     OBJECT
        |    95:     SYNTHETIC
        |    96:     ANNOTATION(16)
        |    98:       TYPEREF 30 [SourceFile]
        |   100:         TERMREFpkg 34 [scala[Qualified . annotation][Qualified . internal]]
        |   102:       APPLY(10)
        |   104:         SELECTin(6) 38 [<init>[Signed Signature(List(java.lang.String),scala.annotation.internal.SourceFile) @<init>]]
        |   107:           NEW
        |   108:             SHAREDtype 98
        |   110:           SHAREDtype 98
        |   112:         STRINGconst 39 [app/src/main/scala/Main.scala]
        |   114:
        |
        | 43 position bytes:
        |   lines: 4
        |   line sizes: 23, 11, 20, 0
        |   positions:
        |         0: 0 .. 56
        |         4: 36 .. 36
        |         7: 36 .. 36
        |        25: 36 .. 56
        |        28: 36 .. 56
        |        36: 36 .. 36
        |        45: 36 .. 36
        |        49: 36 .. 36
        |        53: 36 .. 36
        |        58: 36 .. 36
        |        62: 36 .. 36
        |        72: 36 .. 36
        |        78: 36 .. 36
        |        83: 36 .. 56
        |        87: 47 .. 51
        |        93: 54 .. 56
        |
        | source paths:
        |         0: app/src/main/scala/Main.scala
        |
        |
        | 0 comment bytes:
        |""".stripMargin

  private val cfr =
    s"""|/*
        | * Decompiled with CFR VERSION.
        | */
        |package foo.bar.example;
        |
        |public class Foo {
        |}
        |""".stripMargin

  private val cfrJava =
    s"""|/*
        | * Decompiled with CFR VERSION.
        | */
        |package foo.bar.example;
        |
        |class Main {
        |    Main() {
        |    }
        |}
        |""".stripMargin

  private val cfrMissing =
    s"""|Can't load the class specified:
        |org.benf.cfr.reader.util.CannotLoadClassException: /foo/bar/example/Main.class - java.io.IOException: No such file /foo/bar/example/Main.class
        |""".stripMargin

  private val cfrToplevel =
    s"""|/*
        | * Decompiled with CFR VERSION.
        | */
        |package foo.bar.example;
        |
        |import foo.bar.example.Main$$package$$;
        |
        |public final class Main$$package {
        |    public static void foo() {
        |        Main$$package$$.MODULE$$.foo();
        |    }
        |}
        |""".stripMargin

  private val javapMissing =
    s"""|Error: class not found: Main.class
        |""".stripMargin

  private val javapJava =
    s"""|Compiled from "Main.java"
        |class foo.bar.example.Main {
        |  foo.bar.example.Main();
        |}
        |""".stripMargin

  private val javap =
    s"""|Compiled from "Main.scala"
        |public class foo.bar.example.Foo {
        |  public foo.bar.example.Foo();
        |  private final int foo();
        |}
        |""".stripMargin

  private val javapToplevel =
    s"""|Compiled from "Main.scala"
        |public final class foo.bar.example.Main$$package {
        |  public static void foo();
        |}
        |""".stripMargin

  private def javapVerbose =
    s"""|Compiled from "Main.scala"
        |public class foo.bar.example.Foo
        |  minor version: 0
        |  major version: 52
        |  flags: (0x0021) ACC_PUBLIC, ACC_SUPER
        |  this_class: #2                          // foo/bar/example/Foo
        |  super_class: #4                         // java/lang/Object
        |  interfaces: 0, fields: 0, methods: 2, attributes: 3
        |Constant pool:
        |   #1 = Utf8               foo/bar/example/Foo
        |   #2 = Class              #1             // foo/bar/example/Foo
        |   #3 = Utf8               java/lang/Object
        |   #4 = Class              #3             // java/lang/Object
        |   #5 = Utf8               Main.scala
        |   #6 = Utf8               <init>
        |   #7 = Utf8               ()V
        |   #8 = NameAndType        #6:#7          // "<init>":()V
        |   #9 = Methodref          #4.#8          // java/lang/Object."<init>":()V
        |  #10 = Utf8               this
        |  #11 = Utf8               Lfoo/bar/example/Foo;
        |  #12 = Utf8               foo
        |  #13 = Utf8               ()I
        |  #14 = Utf8               Code
        |  #15 = Utf8               LineNumberTable
        |  #16 = Utf8               LocalVariableTable
        |  #17 = Utf8               SourceFile
        |  #18 = Utf8               TASTY
        |  #19 = Utf8               Scala
        |{
        |  public foo.bar.example.Foo();
        |    descriptor: ()V
        |    flags: (0x0001) ACC_PUBLIC
        |    Code:
        |      stack=1, locals=1, args_size=1
        |         0: aload_0
        |         1: invokespecial #9                  // Method java/lang/Object."<init>":()V
        |         4: return
        |      LineNumberTable:
        |        line 2: 0
        |      LocalVariableTable:
        |        Start  Length  Slot  Name   Signature
        |            0       5     0  this   Lfoo/bar/example/Foo;
        |
        |  private final int foo();
        |    descriptor: ()I
        |    flags: (0x0012) ACC_PRIVATE, ACC_FINAL
        |    Code:
        |      stack=1, locals=1, args_size=1
        |         0: bipush        42
        |         2: ireturn
        |      LineNumberTable:
        |        line 3: 0
        |      LocalVariableTable:
        |        Start  Length  Slot  Name   Signature
        |            0       3     0  this   Lfoo/bar/example/Foo;
        |}
        |SourceFile: "Main.scala"
        |  TASTY: length = 0x10 (unknown attribute)
        |   00 4C ED 51 38 8A 55 00 00 EB FF 96 61 37 65 00
        |
        |  Scala: length = 0x0 (unknown attribute)
        |
        |""".stripMargin

  private val semanticdbCompact =
    s"""|app/src/main/scala/Main.scala
        |-----------------------------
        |
        |Summary:
        |Schema => SemanticDB v4
        |Uri => app/src/main/scala/Main.scala
        |Text => empty
        |Language => Scala
        |Symbols => 4 entries
        |Occurrences => 5 entries
        |
        |Symbols:
        |foo/bar/example/Bar# => class Bar extends Object { self: Bar => +1 decls }
        |foo/bar/example/Bar#`<init>`(). => primary ctor <init>(): Bar
        |foo/bar/example/Foo# => class Foo extends Object { self: Foo => +1 decls }
        |foo/bar/example/Foo#`<init>`(). => primary ctor <init>(): Foo
        |
        |Occurrences:
        |[0:8..0:11) => foo/
        |[0:12..0:15) => foo/bar/
        |[0:16..0:23) <= foo/bar/example/
        |[1:6..1:9) <= foo/bar/example/Foo#
        |[2:6..2:9) <= foo/bar/example/Bar#
        |""".stripMargin

  private val semanticdbDetailed =
    s"""|app/src/main/scala/Main.scala
        |-----------------------------
        |
        |Summary:
        |Schema => SemanticDB v4
        |Uri => app/src/main/scala/Main.scala
        |Text => empty
        |Language => Scala
        |Symbols => 5 entries
        |Occurrences => 7 entries
        |
        |Symbols:
        |foo/bar/example/Bar# => class Bar extends Object { self: Bar => +2 decls }
        |  Object => java/lang/Object#
        |  Bar => foo/bar/example/Bar#
        |foo/bar/example/Bar#`<init>`(). => primary ctor <init>(): Bar
        |  Bar => foo/bar/example/Bar#
        |foo/bar/example/Bar#foo(). => method foo(): Unit
        |  Unit => scala/Unit#
        |foo/bar/example/Foo# => class Foo extends Object { self: Foo => +1 decls }
        |  Object => java/lang/Object#
        |  Foo => foo/bar/example/Foo#
        |foo/bar/example/Foo#`<init>`(). => primary ctor <init>(): Foo
        |  Foo => foo/bar/example/Foo#
        |
        |Occurrences:
        |[0:8..0:11) => foo/
        |[0:12..0:15) => foo/bar/
        |[0:16..0:23) <= foo/bar/example/
        |[1:6..1:9) <= foo/bar/example/Foo#
        |[2:6..2:9) <= foo/bar/example/Bar#
        |[3:6..3:9) <= foo/bar/example/Bar#foo().
        |[3:13..3:17) => scala/Unit#
        |""".stripMargin

  val EventsJarFile: String =
    """|/*******************************************************************************
       |* Copyright (c) 2017 Microsoft Corporation and others.
       |* All rights reserved. This program and the accompanying materials
       |* are made available under the terms of the Eclipse Public License v1.0
       |* which accompanies this distribution, and is available at
       |* http://www.eclipse.org/legal/epl-v10.html
       |*
       |* Contributors:
       |*     Microsoft Corporation - initial API and implementation
       |*******************************************************************************/
       |
       |package com.microsoft.java.debug.core.protocol;
       |
       |import com.microsoft.java.debug.core.protocol.Types.Source;
       |
       |/**
       | * The event types defined by VSCode Debug Protocol.
       | */
       |public class Events {
       |    public static class DebugEvent {
       |        public String type;
       |
       |        public DebugEvent(String type) {
       |            this.type = type;
       |        }
       |    }
       |
       |    public static class InitializedEvent extends DebugEvent {
       |        public InitializedEvent() {
       |            super("initialized");
       |        }
       |    }
       |
       |    public static class StoppedEvent extends DebugEvent {
       |        public long threadId;
       |        public String reason;
       |        public String description;
       |        public String text;
       |        public boolean allThreadsStopped;
       |
       |        /**
       |         * Constructor.
       |         */
       |        public StoppedEvent(String reason, long threadId) {
       |            super("stopped");
       |            this.reason = reason;
       |            this.threadId = threadId;
       |            allThreadsStopped = false;
       |        }
       |
       |        /**
       |         * Constructor.
       |         */
       |        public StoppedEvent(String reason, long threadId, boolean allThreadsStopped) {
       |            this(reason, threadId);
       |            this.allThreadsStopped = allThreadsStopped;
       |        }
       |
       |        /**
       |         * Constructor.
       |         */
       |        public StoppedEvent(String reason, long threadId, boolean allThreadsStopped, String description, String text) {
       |            this(reason, threadId, allThreadsStopped);
       |            this.description = description;
       |            this.text = text;
       |        }
       |    }
       |
       |    public static class ContinuedEvent extends DebugEvent {
       |        public long threadId;
       |        public boolean allThreadsContinued;
       |
       |        /**
       |         * Constructor.
       |         */
       |        public ContinuedEvent(long threadId) {
       |            super("continued");
       |            this.threadId = threadId;
       |        }
       |
       |        /**
       |         * Constructor.
       |         */
       |        public ContinuedEvent(long threadId, boolean allThreadsContinued) {
       |            this(threadId);
       |            this.allThreadsContinued = allThreadsContinued;
       |        }
       |
       |        /**
       |         * Constructor.
       |         */
       |        public ContinuedEvent(boolean allThreadsContinued) {
       |            super("continued");
       |            this.allThreadsContinued = allThreadsContinued;
       |        }
       |    }
       |
       |    public static class ExitedEvent extends DebugEvent {
       |        public int exitCode;
       |
       |        public ExitedEvent(int code) {
       |            super("exited");
       |            this.exitCode = code;
       |        }
       |    }
       |
       |    public static class TerminatedEvent extends DebugEvent {
       |        public boolean restart;
       |
       |        public TerminatedEvent() {
       |            super("terminated");
       |        }
       |
       |        public TerminatedEvent(boolean restart) {
       |            this();
       |            this.restart = restart;
       |        }
       |    }
       |
       |    public static class ThreadEvent extends DebugEvent {
       |        public String reason;
       |        public long threadId;
       |
       |        /**
       |         * Constructor.
       |         */
       |        public ThreadEvent(String reason, long threadId) {
       |            super("thread");
       |            this.reason = reason;
       |            this.threadId = threadId;
       |        }
       |    }
       |
       |    public static class OutputEvent extends DebugEvent {
       |        public enum Category {
       |            console, stdout, stderr, telemetry
       |        }
       |
       |        public Category category;
       |        public String output;
       |        public int variablesReference;
       |        public Source source;
       |        public int line;
       |        public int column;
       |        public Object data;
       |
       |        /**
       |         * Constructor.
       |         */
       |        public OutputEvent(Category category, String output) {
       |            super("output");
       |            this.category = category;
       |            this.output = output;
       |        }
       |
       |        /**
       |         * Constructor.
       |         */
       |        public OutputEvent(Category category, String output, Source source, int line) {
       |            super("output");
       |            this.category = category;
       |            this.output = output;
       |            this.source = source;
       |            this.line = line;
       |        }
       |
       |        public static OutputEvent createConsoleOutput(String output) {
       |            return new OutputEvent(Category.console, output);
       |        }
       |
       |        public static OutputEvent createStdoutOutput(String output) {
       |            return new OutputEvent(Category.stdout, output);
       |        }
       |
       |        /**
       |         * Construct an stdout output event with source info.
       |         */
       |        public static OutputEvent createStdoutOutputWithSource(String output, Source source, int line) {
       |            return new OutputEvent(Category.stdout, output, source, line);
       |        }
       |
       |        public static OutputEvent createStderrOutput(String output) {
       |            return new OutputEvent(Category.stderr, output);
       |        }
       |
       |        /**
       |         * Construct an stderr output event with source info.
       |         */
       |        public static OutputEvent createStderrOutputWithSource(String output, Source source, int line) {
       |            return new OutputEvent(Category.stderr, output, source, line);
       |        }
       |
       |        public static OutputEvent createTelemetryOutput(String output) {
       |            return new OutputEvent(Category.telemetry, output);
       |        }
       |    }
       |
       |    public static class BreakpointEvent extends DebugEvent {
       |        public String reason;
       |        public Types.Breakpoint breakpoint;
       |
       |        /**
       |         * Constructor.
       |         */
       |        public BreakpointEvent(String reason, Types.Breakpoint breakpoint) {
       |            super("breakpoint");
       |            this.reason = reason;
       |            this.breakpoint = breakpoint;
       |        }
       |    }
       |
       |    public static class HotCodeReplaceEvent extends DebugEvent {
       |        public enum ChangeType {
       |            ERROR, WARNING, STARTING, END, BUILD_COMPLETE
       |        }
       |
       |        public ChangeType changeType;
       |        public String message;
       |
       |        /**
       |         * Constructor.
       |         */
       |        public HotCodeReplaceEvent(ChangeType changeType, String message) {
       |            super("hotcodereplace");
       |            this.changeType = changeType;
       |            this.message = message;
       |        }
       |    }
       |
       |    public static class UserNotificationEvent extends DebugEvent {
       |        public enum NotificationType {
       |            ERROR, WARNING, INFORMATION
       |        }
       |
       |        public NotificationType notificationType;
       |        public String message;
       |
       |        /**
       |         * Constructor.
       |         */
       |        public UserNotificationEvent(NotificationType notifyType, String message) {
       |            super("usernotification");
       |            this.notificationType = notifyType;
       |            this.message = message;
       |        }
       |    }
       |}
       |""".stripMargin
}
