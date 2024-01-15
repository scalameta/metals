package tests.pc

import coursierapi.Dependency
import tests.BaseCompletionSuite

class CompletionIssueSuite extends BaseCompletionSuite {

  override protected def extraDependencies(
      scalaVersion: String
  ): Seq[Dependency] = {
    Seq(
      Dependency.of("org.eclipse.lsp4j", "org.eclipse.lsp4j", "0.16.0"),
      if (scalaVersion.startsWith("2.12"))
        Dependency.of("org.scalameta", "scalameta_2.12", "4.6.0")
      else if (scalaVersion.startsWith("2.11"))
        Dependency.of("org.scalameta", "scalameta_2.11", "4.6.0")
      else
        Dependency.of("org.scalameta", "scalameta_2.13", "4.6.0")
    )
  }

  check(
    "comparison",
    """package a
      |object w {
      |  abstract class T(x: Int) {
      |    def met(x: Int): Unit = {
      |      println(x@@)
      |    }
      |  }}
      |""".stripMargin,
    """|x: Int
       |x = : Any""".stripMargin,
    topLines = Some(4),
    compat = Map(
      "2.12" ->
        """|x: Int
           |x = : Any
           |xml scala
           |""".stripMargin,
      "2.11" ->
        """|x: Int
           |x = : Any
           |xml scala
           |""".stripMargin
    )
  )

  check(
    "mutate".tag(IgnoreScala3),
    """package a
      |class Foo@@
      |""".stripMargin,
    ""
  )

  check(
    "issue-569".tag(IgnoreScala3),
    """package a
      |class Main {
      |  new Foo@@
      |}
    """.stripMargin,
    ""
  )

  check(
    "issue-749".tag(IgnoreScala3),
    """package a
      |trait Observable[+A] {
      |  type Self[+T] <: Observable[T]
      |}
      |trait EventStream[+A] extends Observable[A] {
      |  override type Self[+T] = EventStream[T]
      |}
      |class Main {
      |  val stream: EventStream[Int] = ???
      |  stream.@@
      |}
      |""".stripMargin,
    "Self[+T] = Main.this.stream.Self",
    topLines = Some(1)
  )

  checkEdit(
    "issue-753",
    """
      |package a
      |object A {
      |  object Nested{
      |    object NestedLeaf
      |  }
      |}
      |object B {
      |  NestedLea@@
      |}""".stripMargin,
    """|package a
       |object A {
       |  object Nested{
       |    object NestedLeaf
       |  }
       |}
       |object B {
       |  A.Nested.NestedLeaf
       |}
       |""".stripMargin,
    compat = Map(
      "3" -> """|package a
                |
                |import a.A.Nested.NestedLeaf
                |object A {
                |  object Nested{
                |    object NestedLeaf
                |  }
                |}
                |object B {
                |  NestedLeaf
                |}
                |""".stripMargin
    )
  )

  checkEdit(
    "issue-783",
    """
      |package all
      |import all.World.Countries.{
      |  Sweden,
      |  USA
      |}
      |
      |object World {
      |  object Countries{
      |    object Sweden
      |    object Norway
      |    object France
      |    object USA
      |  }
      |}
      |import all.World.Countries.France
      |object B {
      |  val allCountries = Sweden + France + USA + Norway@@
      |}""".stripMargin,
    """|package all
       |import all.World.Countries.{
       |  Sweden,
       |  USA
       |}
       |
       |object World {
       |  object Countries{
       |    object Sweden
       |    object Norway
       |    object France
       |    object USA
       |  }
       |}
       |import all.World.Countries.France
       |object B {
       |  val allCountries = Sweden + France + USA + World.Countries.Norway
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|package all
           |import all.World.Countries.{
           |  Sweden,
           |  USA
           |}
           |import all.World.Countries.Norway
           |
           |object World {
           |  object Countries{
           |    object Sweden
           |    object Norway
           |    object France
           |    object USA
           |  }
           |}
           |import all.World.Countries.France
           |object B {
           |  val allCountries = Sweden + France + USA + Norway
           |}
           |""".stripMargin
    )
  )

  check(
    "issue-813-empty".tag(IgnoreScala211),
    """|package a
       |
       |object Main {
       |  (1 to 10).toList
       |  .map(_ + 1) // comment breaks completions
       |  .@@
       |}
       |""".stripMargin,
    """|::[B >: Int](elem: B): List[B]
       |:::[B >: Int](prefix: List[B]): List[B]
       |""".stripMargin,
    topLines = Some(2),
    compat = Map(
      "2.12" ->
        """|++[B >: Int, That](that: GenTraversableOnce[B])(implicit bf: CanBuildFrom[List[Int],B,That]): That
           |+:[B >: Int, That](elem: B)(implicit bf: CanBuildFrom[List[Int],B,That]): That
           |""".stripMargin
    )
  )

  check(
    "issue-813".tag(IgnoreScala211),
    """|package a
       |
       |object Main {
       |  Array(1, 1,10)
       |  .map(_ + 1) // comment breaks completions
       |  .fil@@
       |}
       |""".stripMargin,
    """|filter(p: Int => Boolean): Array[Int]
       |filterNot(p: Int => Boolean): Array[Int]
       |""".stripMargin,
    topLines = Some(2),
    compat = Map(
      "3" ->
        """|filter(p: A => Boolean): Array[A]
           |filter(pred: A => Boolean): C
           |""".stripMargin
    )
  )

  check(
    "issue-813-space".tag(IgnoreScala211),
    """|package a
       |
       |object Main {
       |  Array(1, 1,10)
       |  .map(_ + 1) // comment breaks completions
       |  . fil@@
       |}
       |""".stripMargin,
    """|filter(p: Int => Boolean): Array[Int]
       |filterNot(p: Int => Boolean): Array[Int]
       |""".stripMargin,
    topLines = Some(2),
    compat = Map(
      "3" ->
        """|filter(p: A => Boolean): Array[A]
           |filter(pred: A => Boolean): C
           |""".stripMargin
    )
  )

  check(
    "issue-813-multi".tag(IgnoreScala211),
    """|package a
       |
       |object Main {
       |  Array(1, 1,10)
       |  .map(_ + 1) /* comment breaks completions */
       |  .fil@@
       |}
       |""".stripMargin,
    """|filter(p: Int => Boolean): Array[Int]
       |filterNot(p: Int => Boolean): Array[Int]
       |""".stripMargin,
    topLines = Some(2),
    compat = Map(
      "3" ->
        """|filter(p: A => Boolean): Array[A]
           |filter(pred: A => Boolean): C
           |""".stripMargin
    )
  )

  checkEdit(
    "issue-1281-import-parens",
    """object obj {
      |  def method(arg: String): Unit = ()
      |}
      |import obj.meth@@
      |""".stripMargin,
    """object obj {
      |  def method(arg: String): Unit = ()
      |}
      |import obj.method""".stripMargin
  )

  // We shouldn't get exhaustive completions for AbsolutePath
  // related to https://github.com/scala/scala/commit/14fa7bef120cbb996d042daba6095530167c49ed
  check(
    "absolute-path",
    """|
       |import scala.meta.io.AbsolutePath
       |object obj {
       |  val path: AbsolutePath = ???
       |  path match@@
       |}
       |""".stripMargin,
    "match"
  )

  // The tests shows `x$1` but it's because the dependency is not indexed
  checkEdit(
    "default-java-override",
    """|import org.eclipse.lsp4j.services.LanguageClient
       |
       |trait Client extends LanguageClient{
       |  over@@
       |}
    """.stripMargin,
    """|import org.eclipse.lsp4j.services.LanguageClient
       |import java.util.concurrent.CompletableFuture
       |import org.eclipse.lsp4j.WorkDoneProgressCreateParams
       |
       |trait Client extends LanguageClient{
       |  override def createProgress(x$1: WorkDoneProgressCreateParams): CompletableFuture[Void] = ${0:???}
       |}
       |""".stripMargin,
    filter = (str) => str.contains("createProgress"),
    compat = Map(
      "3" ->
        """|import org.eclipse.lsp4j.services.LanguageClient
           |import java.util.concurrent.CompletableFuture
           |import org.eclipse.lsp4j.WorkDoneProgressCreateParams
           |
           |trait Client extends LanguageClient{
           |  override def createProgress(x$0: WorkDoneProgressCreateParams): CompletableFuture[Void] = ${0:???}
           |}
           |""".stripMargin
    )
  )

  override val compatProcess: Map[String, String => String] = Map(
    "2.13" -> { s =>
      s.replace(
        "::[B >: Int](x: B): List[B]",
        "::[B >: Int](elem: B): List[B]"
      )
    }
  )
}
