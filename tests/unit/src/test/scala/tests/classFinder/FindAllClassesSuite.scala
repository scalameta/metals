package tests
package classFinder

import java.nio.file.Paths

import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

import munit.Location
import munit.TestOptions

class FindAllClassesSuite extends BaseClassFinderSuite {

  check(
    "only-toplevel",
    """|package a
       |class Foo
       |object Foo
       |class Bar {
       |  val x = 155
       |  class InsideBar
       |  def xx2() = {
       |    class InsideMethod
       |  }
       |}
       |def foo(): Unit = ()
       |def foo2(): Unit = ()
       |""".stripMargin,
    List(
      "Class Foo a.Foo.tasty",
      "Class Bar a.Bar.tasty",
      "Toplevel package a.Main$package.tasty"
    ),
    scalaVersion = V.scala3
  )

  check(
    "all",
    """|package a
       |class Foo
       |object Foo {
       |  class InnerClass
       |}
       |class Bar {
       |  class InnerClass
       |  def xx2() = {
       |    class InsideMethod
       |  }
       |}
       |def foo(): Unit = ()
       |def foo2(): Unit = ()
       |""".stripMargin,
    List(
      "Class Foo a.Foo.class", "Object Foo a.Foo$.class",
      "Class InnerClass a.Foo$InnerClass.class", "Class Bar a.Bar.class",
      "Class InnerClass a.Bar$InnerClass.class",
      "Toplevel package a.Main$package.class"
    ),
    checkInnerClasses = true,
    scalaVersion = V.scala3
  )

  for (scalaVer <- List(V.scala3, V.scala213, V.scala212, V.scala211)) {
    check(
      s"inner-classes (Scala ${scalaVer})",
      """|package a
         |class Foo {
         |  class InnerClass
         |  trait InnerTrait
         |  object InnerObject
         |  class Foo2 {
         |    class Foo3 {
         |      class VeryInnerClass
         |      trait VeryInnerTrait
         |      object VeryInnerObject
         |    }
         |  }
         |}
         |object Foo
         |
         |object Bar {
         |  class InnerClass
         |  trait InnerTrait
         |  object InnerObject
         |  object Bar2 {
         |    object Bar3 {
         |      class VeryInnerClass
         |      trait VeryInnerTrait
         |      object VeryInnerObject
         |    }
         |  }
         |}
         |""".stripMargin,
      List(
        "Class Foo a.Foo.class", "Class InnerClass a.Foo$InnerClass.class",
        "Trait InnerTrait a.Foo$InnerTrait.class",
        "Object InnerObject a.Foo$InnerObject$.class",
        "Class Foo2 a.Foo$Foo2.class", "Class Foo3 a.Foo$Foo2$Foo3.class",
        "Class VeryInnerClass a.Foo$Foo2$Foo3$VeryInnerClass.class",
        "Trait VeryInnerTrait a.Foo$Foo2$Foo3$VeryInnerTrait.class",
        "Object VeryInnerObject a.Foo$Foo2$Foo3$VeryInnerObject$.class",
        "Object Foo a.Foo$.class", "Object Bar a.Bar$.class",
        "Class InnerClass a.Bar$InnerClass.class",
        "Trait InnerTrait a.Bar$InnerTrait.class",
        "Object InnerObject a.Bar$InnerObject$.class",
        "Object Bar2 a.Bar$Bar2$.class", "Object Bar3 a.Bar$Bar2$Bar3$.class",
        "Class VeryInnerClass a.Bar$Bar2$Bar3$VeryInnerClass.class",
        "Trait VeryInnerTrait a.Bar$Bar2$Bar3$VeryInnerTrait.class",
        "Object VeryInnerObject a.Bar$Bar2$Bar3$VeryInnerObject$.class"
      ),
      checkInnerClasses = true,
      scalaVersion = scalaVer
    )
  }

  def check(
      name: TestOptions,
      sourceText: String,
      expected: List[String],
      checkInnerClasses: Boolean = false,
      filename: String = "Main.scala",
      scalaVersion: String = V.scala213
  )(implicit loc: Location): Unit =
    test(name) {
      val (buffers, classFinder) = init(scalaVersion)
      val path = AbsolutePath(Paths.get(filename))
      buffers.put(path, sourceText)
      val classes = classFinder.findAllClasses(path, checkInnerClasses)

      assert(classes.isDefined)
      assertEquals(
        classes.get
          .map(c => s"${c.friendlyName} ${c.description}"),
        expected
      )
    }

}
