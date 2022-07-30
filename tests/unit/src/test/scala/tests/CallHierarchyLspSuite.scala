package tests

class CallHierarchyLspSuite extends BaseCallHierarchySuite("call-hierarchy") {
  test("def-incoming-call") {
    for {
      result <- assertIncomingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  def @@a() {}
           |  def <<b>>/*1*/() { <?<a>?>/*1*/() }
           |  def c() { b() }
           |  def <<d>>/*2*/() { <?<a>?>/*2*/() }
           |}
           |
           |""".stripMargin
      )
      _ <- assertIncomingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  def a() {}
           |  def b() { a() }
           |  def <<c>>/*1*/() { <?<b>?>/*1*/() }
           |  def d() { a() }
           |}
           |
           |""".stripMargin,
        Some(result("1")),
      )
    } yield ()
  }

  test("def-outgoing-call") {
    for {
      result <- assertOutgoingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  def @@a() {
           |    <?<b>?>/*1*/()
           |    <?<c>?>/*2*/()
           |  }
           |  def <<b>>/*1*/() { }
           |  def <<c>>/*2*/() { b() }
           |}
           |
           |""".stripMargin
      )
      _ <- assertOutgoingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  def a() {
           |    b()
           |    c()
           |  }
           |  def <<b>>/*1*/() { }
           |  def c() { <?<b>?>/*1*/() }
           |}
           |
           |""".stripMargin,
        Some(result("2")),
      )
    } yield ()
  }
}
