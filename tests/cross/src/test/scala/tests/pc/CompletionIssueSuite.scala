package tests.pc

import tests.BaseCompletionSuite

object CompletionIssueSuite extends BaseCompletionSuite {
  check(
    "mutate",
    """package a
      |class Foo@@
      |""".stripMargin,
    ""
  )

  check(
    "issue-569",
    """package a
      |class Main {
      |  new Foo@@
      |}
    """.stripMargin,
    ""
  )

  check(
    "issue-749",
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
    """
      |package a
      |import a.A.Nested.NestedLeaf
      |object A {
      |  object Nested{
      |    object NestedLeaf
      |  }
      |}
      |object B {
      |  NestedLeaf
      |}""".stripMargin
  )

}
