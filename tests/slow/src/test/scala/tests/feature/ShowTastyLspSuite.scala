package tests.feature

import scala.collection.JavaConverters._

import scala.meta.internal.metals.DecoderResponse
import scala.meta.internal.metals.{BuildInfo => V}

import org.eclipse.{lsp4j => l}
import tests.BaseLspSuite

class ShowTastyLspSuite extends BaseLspSuite("showTasty") {

  check(
    "open-existing",
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
    new l.Position(1, 1),
    Right(ShowTastyLspSuite.OpenExisting)
  )

  check(
    "handle-multiple-classes",
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
    new l.Position(1, 1),
    Right(ShowTastyLspSuite.MultipleClasses)
  )

  def check(
      testName: String,
      input: String,
      filePath: String,
      position: l.Position,
      expected: Either[String, String]
  ): Unit = {
    test(testName) {
      for {
        _ <- initialize(input)
        _ <- server.didOpen(filePath)
        _ <- server.executeShowTasty(s"file://$workspace/$filePath", position)
      } yield {
        val result = client.clientCommands.asScala
          .filter(_.getCommand() == "metals-show-tasty")
          .head
          .getArguments()
          .asScala
          .head
          .asInstanceOf[DecoderResponse]
        assertEquals(
          if (result.value != null) Right(result.value) else Left(result.error),
          expected
        )
      }
    }

  }
}

object ShowTastyLspSuite {
  private val OpenExisting =
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

  private val MultipleClasses = s"""|Names:
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
}
