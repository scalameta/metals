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

  test("val-incoming-call") {
    for {
      result <- assertIncomingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  val @@a = () => {}
           |  val <<b>>/*1*/ = () => { <?<a>?>/*1*/() }
           |  val c = () => { b() }
           |  val <<d>>/*2*/ = () => { <?<a>?>/*2*/() }
           |}
           |
           |""".stripMargin
      )
      _ <- assertIncomingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  val a = () => {}
           |  val b = () => { a() }
           |  val <<c>>/*1*/ = () => { <?<b>?>/*1*/() }
           |  val d = () => { a() }
           |}
           |
           |""".stripMargin,
        Some(result("1")),
      )
    } yield ()
  }

  test("val-outgoing-call") {
    for {
      result <- assertOutgoingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  val @@a = () => {
           |    <?<b>?>/*1*/()
           |    <?<c>?>/*2*/()
           |  }
           |  val <<b>>/*1*/ = () => { }
           |  val <<c>>/*2*/ = () => { b() }
           |}
           |
           |""".stripMargin
      )
      _ <- assertOutgoingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  val a = () => {
           |    b()
           |    c()
           |  }
           |  val <<b>>/*1*/ = () => { }
           |  val c = () => { <?<b>?>/*1*/() }
           |}
           |
           |""".stripMargin,
        Some(result("2")),
      )
    } yield ()
  }

  test("recursive-incoming-call") {
    for {
      result <- assertIncomingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  def <<fo@@o>>/*1*/() {
           |    <?<foo>?>/*1*/()
           |  }
           |}
           |
           |""".stripMargin
      )
      _ <- assertIncomingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  def foo() {
           |    foo()
           |  }
           |}
           |
           |""".stripMargin,
        Some(result("1")),
      )
    } yield ()
  }

  test("recursive-outgoing-call") {
    for {
      result <- assertOutgoingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  def <<fo@@o>>/*1*/() {
           |    <?<foo>?>/*1*/()
           |  }
           |}
           |
           |""".stripMargin
      )
      _ <- assertOutgoingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  def foo() {
           |    foo()
           |  }
           |}
           |
           |""".stripMargin,
        Some(result("1")),
      )
    } yield ()
  }

  test("indirect-recursive-incoming-call") {
    for {
      result <- assertIncomingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  def @@a() { b() }
           |  def b() { c() }
           |  def <<c>>/*1*/() { <?<a>?>/*1*/() }
           |}
           |
           |""".stripMargin
      )
      result <- assertIncomingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  def a() { b() }
           |  def <<b>>/*1*/() { <?<c>?>/*1*/() }
           |  def c() { a() }
           |}
           |
           |""".stripMargin,
        Some(result("1")),
      )
      result <- assertIncomingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  def <<a>>/*1*/() { <?<b>?>/*1*/() }
           |  def b() { c() }
           |  def c() { a() }
           |}
           |
           |""".stripMargin,
        Some(result("1")),
      )
      _ <- assertIncomingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  def a() { b() }
           |  def b() { c() }
           |  def c() { a() }
           |}
           |
           |""".stripMargin,
        Some(result("1")),
      )
    } yield ()
  }

  test("indirect-recursive-outgoing-call") {
    for {
      result <- assertOutgoingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  def @@a() { <?<b>?>/*1*/() }
           |  def <<b>>/*1*/() { c() }
           |  def c() { a() }
           |}
           |
           |""".stripMargin
      )
      result <- assertOutgoingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  def a() { b() }
           |  def b() { <?<c>?>/*1*/() }
           |  def <<c>>/*1*/() { a() }
           |}
           |
           |""".stripMargin,
        Some(result("1")),
      )
      result <- assertOutgoingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  def <<a>>/*1*/() { b() }
           |  def b() { c() }
           |  def c() { <?<a>?>/*1*/() }
           |}
           |
           |""".stripMargin,
        Some(result("1")),
      )
      _ <- assertIncomingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  def a() { b() }
           |  def b() { c() }
           |  def c() { a() }
           |}
           |
           |""".stripMargin,
        Some(result("1")),
      )
    } yield ()
  }

  test("complex-pats") {
    for {
      _ <- assertIncomingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  val (@@a, b) = (() => (), () => ())
           |  val Array(<<c>>/*1*/, d) = Array(() => { <?<a>?>/*1*/() }, ())
           |}
           |
           |""".stripMargin
      )
      _ <- assertOutgoingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  val (<<a>>/*1*/, b) = (() => (), () => ())
           |  val Array(@@c, d) = Array(() => { <?<a>?>/*1*/() }, ())
           |}
           |
           |""".stripMargin
      )
    } yield ()
  }
}
