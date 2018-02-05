package tests.mtags

import java.nio.file.Paths
import scala.meta.languageserver.compiler.CompilerConfig
import scala.meta.languageserver.mtags.Mtags
import org.langmeta.internal.semanticdb.XtensionSchemaTextDocuments

object JavaMtagsTest extends BaseMtagsTest {
  check(
    "interface.java",
    """package a.b;
      |interface A {
      |  public String a();
      |}
      |""".stripMargin,
    """
      |Language:
      |Java
      |
      |Names:
      |[8..9): a => _root_.a.
      |[10..11): b => _root_.a.b.
      |[23..24): A <= _root_.a.b.A.
      |[23..24): A <= _root_.a.b.A#
      |[43..44): a <= _root_.a.b.A#a.
      |
      |Symbols:
      |_root_.a. => javadefined package a
      |_root_.a.b. => javadefined package b
      |_root_.a.b.A# => javadefined trait A
      |_root_.a.b.A#a. => javadefined def a
      |_root_.a.b.A. => javadefined object A
      |""".stripMargin
  )

  check(
    "class.java",
    """
      |class B {
      |  public static void c() { }
      |  public int d() { }
      |  public class E {}
      |  public static class F {}
      |}
    """.stripMargin,
    """
      |Language:
      |Java
      |
      |Names:
      |[7..8): B <= _root_.B.
      |[7..8): B <= _root_.B#
      |[18..19): c <= _root_.B.c.
      |[53..54): d <= _root_.B#d.
      |[76..77): E <= _root_.B#E.
      |[76..77): E <= _root_.B#E#
      |[103..104): F <= _root_.B.F.
      |[103..104): F <= _root_.B.F#
      |
      |Symbols:
      |_root_.B# => javadefined class B
      |_root_.B#E# => javadefined class E
      |_root_.B#E. => javadefined object E
      |_root_.B#d. => javadefined def d
      |_root_.B. => javadefined object B
      |_root_.B.F# => javadefined class F
      |_root_.B.F. => javadefined object F
      |_root_.B.c. => javadefined def c
    """.stripMargin
  )

  check(
    "enum.java",
    """
      |enum G {
      |  H,
      |  I
      |}
    """.stripMargin,
    """
      |
      |Language:
      |Java
      |
      |Names:
      |[6..7): G <= _root_.G.
      |[12..13): H <= _root_.G.H.
      |[12..13): H <= _root_.G.H.
      |[17..18): I <= _root_.G.I.
      |[17..18): I <= _root_.G.I.
      |
      |Symbols:
      |_root_.G. => javadefined object G
      |_root_.G.H. => javadefined val H
      |_root_.G.H. => javadefined val H
      |_root_.G.I. => javadefined val I
      |_root_.G.I. => javadefined val I
      |""".stripMargin
  )

  check(
    "field.java",
    """
      |public class J {
      |    public static final int FIELD = 1;
      |}
    """.stripMargin,
    """
      |Language:
      |Java
      |
      |Names:
      |[14..15): J <= _root_.J.
      |[14..15): J <= _root_.J#
      |[46..51): FIELD <= _root_.J.FIELD.
      |
      |Symbols:
      |_root_.J# => javadefined class J
      |_root_.J. => javadefined object J
      |_root_.J.FIELD. => javadefined val FIELD
    """.stripMargin
  )

//  I came across this example here
//  {{{
//  public interface Extension {
//    Set<Extension> EMPTY_SET = new HashSet<Extension>();
//  }
//  }}}
//  from Flexmark where EMPTY_SET is static but doesn't have isStatic = true.
// JavaMtags currently marks it as Extension#EMPTY_SET but scalac sees it as Extension.EMPTY_SET
  checkIgnore(
    "default.java",
    """package k;
      |public interface K {
      |  L l = new L;
      |}
    """.stripMargin,
    """
      |Language:
      |Java
      |
      |Names:
      |[8..9): k => _root_.k.
      |[28..29): K <= _root_.k.K.
      |[28..29): K <= _root_.k.K#
      |[36..37): l <= _root_.k.K.l.
      |
      |Symbols:
      |_root_.k. => javadefined package k
      |_root_.k.K# => javadefined trait K
      |_root_.k.K#m. => javadefined def m
      |_root_.k.K. => javadefined object K
    """.stripMargin
  )

  test("index a few sources from the JDK") {
    val jdk = CompilerConfig.jdkSources.get
    val DefaultFileSystem =
      Paths.get("java").resolve("io").resolve("DefaultFileSystem.java")
    val db = Mtags.indexDatabase(jdk :: Nil, shouldIndex = { path =>
      path.toNIO.endsWith(DefaultFileSystem)
    })
    val obtained = db
      .toDb(None)
      .syntax
      .replace(jdk.toString(), "JAVA_HOME")
      .replaceAll("-+", "------------------") // consistent across machines.
    val expected =
      """
        |jar:file://JAVA_HOME!/java/io/DefaultFileSystem.java
        |------------------
        |Language:
        |Java
        |
        |Names:
        |[219..223): java => _root_.java.
        |[224..226): io => _root_.java.io.
        |[260..277): DefaultFileSystem <= _root_.java.io.DefaultFileSystem.
        |[260..277): DefaultFileSystem <= _root_.java.io.DefaultFileSystem#
        |[387..400): getFileSystem <= _root_.java.io.DefaultFileSystem.getFileSystem.
        |
        |Symbols:
        |_root_.java. => javadefined package java
        |_root_.java.io. => javadefined package io
        |_root_.java.io.DefaultFileSystem# => javadefined class DefaultFileSystem
        |_root_.java.io.DefaultFileSystem. => javadefined object DefaultFileSystem
        |_root_.java.io.DefaultFileSystem.getFileSystem. => javadefined def getFileSystem
      """.stripMargin
    assertNoDiff(obtained, expected)
  }

  // Ignored because it's slow
  ignore("index JDK") {
    val db = Mtags.indexDatabase(CompilerConfig.jdkSources.get :: Nil)
    pprint.log(db.documents.length)
  }
}
