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

  test("primary-constructor") {
    for {
      _ <- assertIncomingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |class F@@oo(x: Int)
           |
           |object Main {
           |  def <<foo>>/*1*/(x: Int) = new <?<Foo>?>/*1*/(x)
           |}
           |
           |""".stripMargin
      )
      _ <- assertOutgoingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |class Foo<<>>/*1*/(x: Int)
           |
           |object Main {
           |  def fo@@o(x: Int) = new <?<Foo>?>/*1*/(x)
           |}
           |
           |""".stripMargin
      )
    } yield ()
  }

  test("secondary-constructor") {
    for {
      _ <- assertIncomingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |class Foo(x: Int) {
           |  def t@@his(x: Int, y: Int) = this(x + y)
           |}
           |
           |object Main {
           |  def <<foo>>/*1*/(x: Int, y: Int) = new <?<Foo>?>/*1*/(x, y)
           |}
           |
           |""".stripMargin
      )
      _ <- assertOutgoingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |class Foo(x: Int) {
           |  def <<this>>/*1*/(x: Int, y: Int) = this(x + y)
           |}
           |
           |object Main {
           |  def f@@oo(x: Int, y: Int) = new <?<Foo>?>/*1*/(x, y)
           |}
           |
           |""".stripMargin
      )
    } yield ()
  }

  test("case-class-constructor") {
    for {
      _ <- assertIncomingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |case class F@@oo(x: Int)
           |
           |object Main {
           |  def <<foo>>/*1*/(x: Int) = <?<Foo>?>/*1*/(x)
           |}
           |
           |""".stripMargin
      )
      _ <- assertOutgoingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |case class Foo<<>>/*1*/(x: Int)
           |
           |object Main {
           |  def fo@@o(x: Int) = <?<Foo>?>/*1*/(x)
           |}
           |
           |""".stripMargin
      )
    } yield ()
  }

  test("apply") {
    for {
      _ <- assertIncomingCalls(
        """|/a/src/main/scala/a/User.scala
           |package a
           |
           |class User(val name: String, val age: Int)
           |
           |object User {
           |  def ap@@ply(name: String) = new User(name, 0)
           |}
           |
           |/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  def <<foo>>/*1*/() {
           |    User.<?<apply>?>/*1*/("Foo")
           |    <?<User>?>/*1*/("Bar")
           |  }
           |}
           |
           |""".stripMargin
      )
      _ <- assertOutgoingCalls(
        """|/a/src/main/scala/a/User.scala
           |package a
           |
           |class User(val name: String, val age: Int)
           |
           |object <<User>>/*2*/ {
           |  def <<apply>>/*1*/(name: String) = new User(name, 0)
           |}
           |
           |/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  def f@@oo() {
           |    <?<User>?>/*2*/.<?<apply>?>/*1*/("Foo")
           |    <?<User>?>/*1,2*/("Bar")
           |  }
           |}
           |
           |""".stripMargin
      )
    } yield ()
  }

  test("unapply") {
    for {
      _ <- assertIncomingCalls(
        """|/a/src/main/scala/a/User.scala
           |package a
           |
           |class User(val name: String, val age: Int)
           |
           |object User {
           |  def un@@apply(user: User): Option[(String, Int)] =
           |     Some(user.name, user.age)
           |}
           |
           |/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  def <<foo>>/*1*/(user: User) {
           |    user match {
           |      case <?<User>?>/*1*/(name, age) => 
           |        println(s"My name is $name and I'm $age")
           |    }
           |  }
           |}
           |
           |""".stripMargin
      )

      _ <- assertOutgoingCalls(
        """|/a/src/main/scala/a/User.scala
           |package a
           |
           |class User(val name: String, val age: Int)
           |
           |object <<User>>/*2*/ {
           |  def <<unapply>>/*1*/(user: User): Option[(String, Int)] =
           |     Some(user.name, user.age)
           |}
           |
           |/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  def fo@@o(user: User) {
           |    user match {
           |      case <?<User>?>/*1,2*/(name, age) => 
           |        println(s"My name is $name and I'm $age")
           |    }
           |  }
           |}
           |
           |""".stripMargin
      )
    } yield ()
  }

  test("decl-incoming-call") {
    for {
      _ <- assertIncomingCalls(
        """|/a/src/main/scala/a/Demo.scala
           |package a
           |
           |trait Service[R] {
           |  def ge@@t(): R
           |}
           |
           |object Demo {
           |  val r: Service[String] = ???
           |  def <<main>>/*1*/() = r.<?<get>?>/*1*/()
           |}
           |
           |""".stripMargin
      )
    } yield ()
  }

  test("decl-outgoing-call") { // https://github.com/scalameta/metals/issues/4489
    for {
      _ <- assertOutgoingCalls(
        """|/a/src/main/scala/a/Demo.scala
           |package a
           |
           |trait Service[R] {
           |  def ge@@t(): R
           |}
           |
           |object Demo {
           |  val r: Service[String] = ???
           |  def main() = r.get()
           |}
           |
           |""".stripMargin
      )
    } yield ()
  }

}
