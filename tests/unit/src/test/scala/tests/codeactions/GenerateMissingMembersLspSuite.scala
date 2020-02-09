package tests.codeactions

import scala.meta.internal.metals.codeactions.GenerateMissingMembers

class GenerateMissingMembersLspSuite
    extends BaseCodeActionLspSuite("genereteMissingMembers") {

  import scala.meta.internal.metals.ScalacDiagnostic

  check(
    "for class",
    """|package a
       |
       |trait A {
       |  def foo(i: Int): String
       |}
       |
       |<<class B extends A>>
       |""".stripMargin,
    GenerateMissingMembers.title,
    """|package a
       |
       |trait A {
       |  def foo(i: Int): String
       |}
       |
       |class B extends A {
       |  override def foo(i: Int): String = ???
       |}
       |""".stripMargin
  )

  check(
    "for object",
    """|package a
       |
       |trait A {
       |  def foo(i: Int): String
       |}
       |
       |<<object B extends A>>
       |""".stripMargin,
    GenerateMissingMembers.title,
    """|package a
       |
       |trait A {
       |  def foo(i: Int): String
       |}
       |
       |object B extends A {
       |  override def foo(i: Int): String = ???
       |}
       |""".stripMargin
  )

  check(
    "with type args",
    """|package a
       |
       |trait A[X, F[_]] {
       |  def foo(i: Int): F[X]
       |}
       |
       |<<class B[Z, G[_]] extends A[Z, G]>>
       |""".stripMargin,
    GenerateMissingMembers.title,
    """|package a
       |
       |trait A[X, F[_]] {
       |  def foo(i: Int): F[X]
       |}
       |
       |class B[Z, G[_]] extends A[Z, G] {
       |  override def foo(i: Int): G[Z] = ???
       |}
       |""".stripMargin
  )

  check(
    "respects identation",
    """|package a
       |
       |trait A {
       |  def foo(i: Int): String
       |}
       |object x {
       |  <<class B extends A>>
       |}
       |""".stripMargin,
    GenerateMissingMembers.title,
    """|package a
       |
       |trait A {
       |  def foo(i: Int): String
       |}
       |object x {
       |  class B extends A {
       |    override def foo(i: Int): String = ???
       |  }
       |}
       |""".stripMargin
  )

  check(
    "adds only missing",
    """|package a
       |
       |trait A {
       |  def foo(i: Int): String
       |  def bar(i: Int): String
       |}
       |object x {
       |  <<class B extends A {
       |    override def foo(i: Int): String = ???
       |  }>>
       |}
       |""".stripMargin,
    GenerateMissingMembers.title,
    """|package a
       |
       |trait A {
       |  def foo(i: Int): String
       |  def bar(i: Int): String
       |}
       |object x {
       |  class B extends A {
       |    override def foo(i: Int): String = ???
       |    override def bar(i: Int): String = ???
       |  }
       |}
       |""".stripMargin
  )

  test("parse diagnostic") {
    val messages = List(
      "object creation impossible, since method a in trait X of type ()Unit is not defined",
      "needs to be abstract, since method a in trait X of type ()Unit is not defined",
      "since:\nit has 2 unimplemented members.",
      "since value a in class SD of type Int is not defined"
    )
    messages.foreach(m => {
      assert(ScalacDiagnostic.UnimplementedMembers.matches(m))
    })
  }
}
