package tests
package classFinder

import java.nio.file.Paths

import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

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

  check(
    "inner-classes",
    """|package a
       |class Foo {
       |  class InnerClass
       |}
       |object Foo
       |
       |object Bar {
       |  class InnerClass
       |}
       |""".stripMargin,
    List(
      "Class Foo a.Foo.class",
      "Class InnerClass a.Foo$InnerClass.class",
      "Object Foo a.Foo$.class",
      "Object Bar a.Bar$.class",
      "Class InnerClass a.Bar$InnerClass.class"
    ),
    checkInnerClasses = true,
    scalaVersion = V.scala3
  )

  def check(
      name: TestOptions,
      sourceText: String,
      expected: List[String],
      checkInnerClasses: Boolean = false,
      filename: String = "Main.scala",
      scalaVersion: String = V.scala213
  ): Unit =
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
