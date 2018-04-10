package tests.mtags

import java.nio.file.Paths
import scala.meta.metals.compiler.CompilerConfig
import scala.meta.metals.mtags.Mtags
import org.langmeta.internal.semanticdb._

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
      |[8..9): a => a.
      |[10..11): b => a.b.
      |[23..24): A <= a.b.A#
      |[43..44): a <= a.b.A#a.
      |
      |Symbols:
      |a. => javadefined package a
      |a.b. => javadefined package b
      |a.b.A# => javadefined interface A
      |a.b.A#a. => javadefined method a
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
      |[7..8): B <= B#
      |[18..19): c <= B#c.
      |[53..54): d <= B#d.
      |[76..77): E <= B#E#
      |[103..104): F <= B#F#
      |
      |Symbols:
      |B# => javadefined class B
      |B#E# => javadefined class E
      |B#F# => javadefined class F
      |B#c. => javadefined method c
      |B#d. => javadefined method d
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
      |[6..7): G <= G#
      |[12..13): H <= G#H.
      |[17..18): I <= G#I.
      |
      |Symbols:
      |G# => javadefined enum class G
      |G#H. => javadefined field H
      |G#I. => javadefined field I
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
      |[14..15): J <= J#
      |[46..51): FIELD <= J#FIELD.
      |
      |Symbols:
      |J# => javadefined class J
      |J#FIELD. => javadefined field FIELD
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
      |[8..9): k => k.
      |[28..29): K <= k.K.
      |[28..29): K <= k.K#
      |[36..37): l <= k.K.l.
      |
      |Symbols:
      |k. => javadefined package k
      |k.K# => javadefined interface K
      |k.K#m. => javadefined method m
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
        |[219..223): java => java.
        |[224..226): io => java.io.
        |[260..277): DefaultFileSystem <= java.io.DefaultFileSystem#
        |[387..400): getFileSystem <= java.io.DefaultFileSystem#getFileSystem.
        |
        |Symbols:
        |java. => javadefined package java
        |java.io. => javadefined package io
        |java.io.DefaultFileSystem# => javadefined class DefaultFileSystem
        |java.io.DefaultFileSystem#getFileSystem. => javadefined method getFileSystem
      """.stripMargin
    assertNoDiff(obtained, expected)
  }

  // Ignored because it's slow
  ignore("index JDK") {
    val db = Mtags.indexDatabase(CompilerConfig.jdkSources.get :: Nil)
    pprint.log(db.documents.length)
  }
}
