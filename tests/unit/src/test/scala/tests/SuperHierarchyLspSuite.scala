package tests

import scala.meta.internal.metals.{BuildInfo => V}

class SuperHierarchyLspSuite
    extends BaseSuperHierarchySuite("super-method-hierarchy") {

  test("simple") {
    val code =
      """
        |package a
        |trait A { def <<1>>xxx: String = "A" }
        |trait B extends A
        |trait C extends A { override def <<3>>xxx: String = "C -> " + super.xxx }
        |trait D extends C { override def <<4>>xxx: String = "D -> " + super.xxx }
        |""".stripMargin
    checkHierarchy(
      code,
      Map(
        3 -> List("a.A#xxx"),
        4 -> List("a.C#xxx", "a.A#xxx"),
      ),
    )
  }

  test("complex") {
    val code =
      """
        |package a
        |trait A { def <<1>>xxx: String = "A" }
        |trait B1 extends A
        |trait B2 extends B1
        |trait B3 extends B2 { override def <<2>>xxx: String = "B3 -> " + super.xxx }
        |
        |trait C1 extends A
        |trait C2 extends C1 { override def <<3>>xxx: String = "C2 -> " + super.xxx }
        |trait C3 extends C2 { override def <<4>>xxx: String = "C3 -> " + super.xxx }
        |
        |trait D1 extends B1 { override def <<5>>xxx: String = "D1 -> " + super.xxx }
        |trait D2 extends B2
        |trait D3 extends B3
        |
        |class E1 extends A with C3 with B3 { override def <<6>>xxx: String = "E1 -> " + super.xxx }
        |class E2 extends A with C3 with B2 { override def <<7>>xxx: String = "E2 -> " + super.xxx }
        |class E3 extends A with C2 with B2 { override def <<8>>xxx: String = "E3 -> " + super.xxx }
        |class E4 extends A with C1 with B2 { override def <<9>>xxx: String = "E4 -> " + super.xxx }
        |
        |class E5 extends D1 with C2 with B2 { override def <<10>>xxx: String = "E5 -> " + super.xxx }
        |class E6 extends D1 with C1 with B2 { override def <<11>>xxx: String = "E6 -> " + super.xxx }
        |class E7 extends D3 with C2 with B3 { override def <<12>>xxx: String = "E7 -> " + super.xxx }
        |class E8 extends D3 with C1 with B3 { override def <<13>>xxx: String = "E8 -> " + super.xxx }
        |""".stripMargin
    checkHierarchy(
      code,
      Map(
        2 -> List("a.A#xxx"),
        3 -> List("a.A#xxx"),
        4 -> List("a.C2#xxx", "a.A#xxx"),
        // D1
        5 -> List("a.A#xxx"),
        // E1-E4
        6 -> List("a.B3#xxx", "a.C3#xxx", "a.C2#xxx", "a.A#xxx"),
        7 -> List("a.C3#xxx", "a.C2#xxx", "a.A#xxx"),
        8 -> List("a.C2#xxx", "a.A#xxx"),
        9 -> List("a.A#xxx"),
        // E5-E8
        10 -> List("a.C2#xxx", "a.D1#xxx", "a.A#xxx"),
        11 -> List("a.D1#xxx", "a.A#xxx"),
        12 -> List("a.C2#xxx", "a.B3#xxx", "a.A#xxx"),
        13 -> List("a.B3#xxx", "a.A#xxx"),
      ),
    )
  }

  test("with external dep", withoutVirtualDocs = true) {
    val code =
      """
        |package a
        |import io.circe.Decoder
        |
        |trait CustomDecoder[X] extends Decoder[String] {
        |  override def <<1>>apply(c: io.circe.HCursor): Decoder.Result[String] = ???
        |}
        |
        |class CustomXDecoder extends CustomDecoder[Unit] {
        |  override def <<2>>apply(c: io.circe.HCursor): Decoder.Result[String] = ???
        |}
        |
        |""".stripMargin
    checkHierarchy(
      code,
      Map(
        1 -> List("io.circe.Decoder#apply"),
        2 -> List("a.CustomDecoder#apply", "io.circe.Decoder#apply"),
      ),
    )
  }

  test("library members") {
    checkHierarchy(
      libraryMembers,
      Map(
        1 -> List(
          "scala.collection.StrictOptimizedIterableOps#map",
          "scala.collection.IterableOps#map",
          "scala.collection.IterableOnceOps#map",
        ),
        2 -> List("java.lang.CharSequence#length"),
        3 -> List(
          "scala.Function1#toString",
          "scala.collection.Iterable#toString",
        ),
        4 -> List(
          "scala.math.Ordering#compare",
          "java.util.Comparator#compare",
        ),
        5 -> List(),
        6 -> List(
          ";java.lang.AbstractStringBuilder#append",
          "java.lang.Appendable#append",
        ),
      ),
    )
  }

  test("library members scala 3") {
    checkHierarchy(
      libraryMembers,
      Map(
        1 -> List(
          "scala.collection.StrictOptimizedIterableOps#map",
          "scala.collection.IterableOps#map",
          "scala.collection.IterableOnceOps#map",
        ),
        2 -> List("java.lang.CharSequence#length"),
        3 -> List(
          "scala.Function1#toString",
          "scala.collection.Iterable#toString",
        ),
        4 -> List(
          "scala.math.Ordering#compare",
          "java.util.Comparator#compare",
        ),
        5 -> List(),
        6 -> List(
          "java.lang.AbstractStringBuilder#append",
          "java.lang.Appendable#append",
        ),
      ),
      V.scala3,
    )
  }

  test("library members latest scala 3") {
    checkHierarchy(
      libraryMembers,
      Map(
        1 -> List(
          "scala.collection.StrictOptimizedIterableOps#map",
          "scala.collection.IterableOps#map",
          "scala.collection.IterableOnceOps#map",
        ),
        2 -> List("java.lang.CharSequence#length"),
        3 -> List(
          "scala.Function1#toString",
          "scala.collection.Iterable#toString",
        ),
        4 -> List(
          "scala.math.Ordering#compare",
          "java.util.Comparator#compare",
        ),
        5 -> List(),
        6 -> List(
          "java.lang.AbstractStringBuilder#append",
          "java.lang.Appendable#append",
        ),
      ),
      V.latestScala3Next,
    )
  }

  test("scala 3 library member") {
    checkHierarchy(
      """
        |package a
        |object Main {
        |  val p = scala.runtime.TupleXXL(1, 2).<<1>>productPrefix
        |}
        |""".stripMargin,
      Map(1 -> List("scala.Product#productPrefix")),
      V.scala3,
    )
  }

  test("library overloads") {
    checkHierarchy(
      libraryOverloads,
      Map(
        1 -> List("scala/collection/mutable/Buffer#remove()."),
        2 -> List("scala/collection/mutable/Buffer#remove(+1)."),
      ),
      rawSymbols = true,
    )
  }

  test("library overloads scala 3") {
    checkHierarchy(
      libraryOverloads,
      Map(
        1 -> List("scala/collection/mutable/Buffer#remove()."),
        2 -> List("scala/collection/mutable/Buffer#remove(+1)."),
      ),
      V.scala3,
      rawSymbols = true,
    )
  }

  private val workspaceMember =
    """
      |package a
      |object Main {
      |  val s = new Impl().<<1>>xxx
      |}
      |""".stripMargin

  private val workspaceMemberFile =
    """|/a/src/main/scala/a/Impl.scala
       |package a
       |trait Base { def xxx: String = "Base" }
       |class Impl extends Base { override def xxx: String = "Impl" }
       |""".stripMargin

  test("workspace member in another file") {
    checkHierarchy(
      workspaceMember,
      Map(1 -> List("a.Base#xxx")),
      otherFiles = workspaceMemberFile,
    )
  }

  test("workspace member in another file scala 2.12") {
    checkHierarchy(
      workspaceMember,
      Map(1 -> List("a.Base#xxx")),
      V.scala212,
      otherFiles = workspaceMemberFile,
    )
  }

  test("workspace member in another file scala 3") {
    checkHierarchy(
      workspaceMember,
      Map(1 -> List("a.Base#xxx")),
      V.scala3,
      otherFiles = workspaceMemberFile,
    )
  }

  private val javaMember =
    """
      |package a;
      |public class J {
      |  int n = "a".<<1>>length();
      |  StringBuilder b = new StringBuilder().<<2>>append('c');
      |}
      |""".stripMargin

  private val javaFile = "a/src/main/java/a/J.java"

  test("java member in a mixed target") {
    checkHierarchy(
      javaMember,
      Map(1 -> List("java.lang.CharSequence#length"), 2 -> List()),
      file = javaFile,
    )
  }

  test("java member in a mixed target scala 3") {
    checkHierarchy(
      javaMember,
      Map(1 -> List("java.lang.CharSequence#length"), 2 -> List()),
      V.scala3,
      file = javaFile,
    )
  }

  test("java member in a java-only target") {
    checkHierarchy(
      javaMember,
      Map(1 -> List(), 2 -> List()),
      file = javaFile,
      javaOnly = true,
    )
  }

  test("java member with javac release 11") {
    checkHierarchy(
      javaMember,
      Map(1 -> List("java.lang.CharSequence#length"), 2 -> List()),
      file = javaFile,
      javacOptions = List("--release", "11"),
    )
  }

  test("java member with javac release 11 scala 3") {
    checkHierarchy(
      javaMember,
      Map(1 -> List("java.lang.CharSequence#length"), 2 -> List()),
      V.scala3,
      file = javaFile,
      javacOptions = List("--release", "11"),
    )
  }

  test("library member in a dependency source") {
    checkDependencySource(
      V.scala213,
      List("scala.collection.IterableOnceOps#toList"),
    )
  }

  test("library member in a dependency source scala 3") {
    checkDependencySource(
      V.scala3,
      List("scala.collection.IterableOnceOps#toList"),
    )
  }

}
