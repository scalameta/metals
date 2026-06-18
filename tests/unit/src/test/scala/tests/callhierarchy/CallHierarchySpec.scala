package tests.callhierarchy

import scala.meta.internal.metals.AutoImportBuildKind
import scala.meta.internal.metals.Configs.ReferenceProviderConfig
import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mbt.MbtBuildServer

import tests.BaseCallHierarchySuite
import tests.BuildInfo

class CallHierarchyLspSuite extends CallHierarchySpec {
  override def withMbt: Boolean = false
}

class CallHierarchyMbtSuite extends CallHierarchySpec {
  override def withMbt: Boolean = true

  override def initializeGitRepo: Boolean = true

  override def userConfig: UserConfiguration =
    super.userConfig.copy(
      fallbackScalaVersion = Some(BuildInfo.scalaVersion),
      workspaceSymbolProvider = WorkspaceSymbolProviderConfig.mbt,
      referenceProvider = ReferenceProviderConfig.mbt,
      preferredBuildServer = Some(MbtBuildServer.name),
      automaticImportBuild = AutoImportBuildKind.All,
    )
}

abstract class CallHierarchySpec
    extends BaseCallHierarchySuite("call-hierarchy") {

  test("def-incoming-call") {
    for {
      result <- assertIncomingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  def @@a() = {}
           |  def <<b>>/*1*/() = { <?<a>?>/*1*/() }
           |  def c() = { b() }
           |  def <<d>>/*2*/() = { <?<a>?>/*2*/() }
           |}
           |/a/src/main/scala/a/Other.scala
           |package a
           |
           |object Other {
           |  def <<e>>/*3*/() = { Main.<?<a>?>/*3*/() }
           |}
           |
           |""".stripMargin
      )
      _ <- assertIncomingCalls(
        input = """|/a/src/main/scala/a/Main.scala
                   |package a
                   |
                   |object Main {
                   |  def a() = {}
                   |  def b() = { a() }
                   |  def <<c>>/*1*/() = { <?<b>?>/*1*/() }
                   |  def d() = { a() }
                   |}
                   |
                   |""".stripMargin,
        item = Some(result("1")),
      )
    } yield ()
  }

  test("def-incoming-call-references") {
    for {
      result <- assertIncomingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  def a() = {}
           |  def <<b>>/*1*/() = { <?<@@a>?>/*1*/() }
           |  def c() = { b() }
           |  def <<d>>/*2*/() = { <?<a>?>/*2*/() }
           |}
           |/a/src/main/scala/a/Other.scala
           |package a
           |
           |object Other {
           |  def <<e>>/*3*/() = { Main.<?<a>?>/*3*/() }
           |}
           |
           |""".stripMargin
      )
      _ <- assertIncomingCalls(
        input = """|/a/src/main/scala/a/Main.scala
                   |package a
                   |
                   |object Main {
                   |  def a() = {}
                   |  def b() = { a() }
                   |  def <<c>>/*1*/() = { <?<b>?>/*1*/() }
                   |  def d() = { a() }
                   |}
                   |
                   |""".stripMargin,
        item = Some(result("1")),
      )
    } yield ()
  }

  test("def-incoming-call-interface") {
    for {
      _ <- assertIncomingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |trait A {
           |  def @@a()
           |}
           |
           |object Main extends A {
           |  override def <<a>>/*1*/() = { Main.<?<a>?>/*1*/() }
           |}
           |
           |/a/src/main/scala/a/Other.scala
           |package a
           |
           |object Other {
           |  val a: A = Main
           |  def <<one>>/*2*/() = {
           |    a.<?<a>?>/*2*/()
           |  }
           |  def <<two>>/*3*/() = {
           |    a.<?<a>?>/*3*/()
           |  }
           |  def <<three>>/*4*/() = {
           |    Main.<?<a>?>/*4*/()
           |  }
           |}
           |
           |""".stripMargin
      )
    } yield ()
  }

  test("def-incoming-call-interface-2") {
    for {
      _ <- assertIncomingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |trait A {
           |  def a()
           |}
           |
           |object Main extends A {
           |  override def @@a() = {  }
           |}
           |
           |/a/src/main/scala/a/Other.scala
           |package a
           |
           |object Other {
           |  val a: A = Main
           |  def <<one>>/*1*/() = {
           |    a.<?<a>?>/*1*/()
           |  }
           |  def <<two>>/*2*/() = {
           |    a.<?<a>?>/*2*/()
           |  }
           |  def <<three>>/*3*/() = {
           |    Main.<?<a>?>/*3*/()
           |  }
           |}
           |
           |""".stripMargin
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
           |  def @@a() = {
           |    <?<b>?>/*1*/()
           |    <?<c>?>/*2*/()
           |    Other.<?<e>?>/*3*/()
           |  }
           |  def <<b>>/*1*/() = { }
           |  def <<c>>/*2*/() = { b() }
           |}
           |
           |/a/src/main/scala/a/Other.scala
           |package a
           |
           |object Other {
           |  def <<e>>/*3*/() = { Main.a() }
           |}
           |
           |""".stripMargin
      )
      _ <- assertOutgoingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  def a() ={
           |    b()
           |    c()
           |    Other.e()
           |  }
           |  def <<b>>/*1*/() = { }
           |  def c() = { <?<b>?>/*1*/() }
           |}
           |
           |""".stripMargin,
        Some(result("2")),
      )
    } yield ()
  }

  test("java-incoming-call") {
    for {
      _ <- assertIncomingCalls(
        """|/a/src/main/java/a/Main.java
           |package a;
           |
           |public class Main {
           |  public void @@a() {}
           |  public void <<b>>/*1*/() { <?<a>?>/*1*/(); }
           |  public void c() { b(); }
           |  public void <<d>>/*2*/() { <?<a>?>/*2*/(); }
           |}
           |
           |""".stripMargin
      )
    } yield ()
  }

  test("java-incoming-call-constructor") {
    for {
      _ <- assertIncomingCalls(
        """|/a/src/main/java/a/Main.java
           |package a;
           |
           |public class Main {
           |  public <<Main>>/*1*/() {
           |    <?<c>?>/*1*/();
           |    <?<c>?>/*1*/();
           |    b();
           |  }
           |  public void <<b>>/*2*/() { <?<c>?>/*2*/(); }
           |  public void @@c() { b(); }
           |  public void d() { b(); }
           |}
           |
           |""".stripMargin
      )
    } yield ()
  }

  test("java-outgoing-call") {
    for {
      _ <- assertOutgoingCalls(
        """|/a/src/main/java/a/Main.java
           |package a;
           |
           |public class Main {
           |  public void @@a() {
           |    <?<b>?>/*1*/();
           |    <?<c>?>/*2*/();
           |  }
           |  public void <<b>>/*1*/() { }
           |  public void <<c>>/*2*/() { b(); }
           |  public void d() { b(); }
           |}
           |
           |""".stripMargin
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

  test("primary-constructor-incoming") {
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
    } yield ()
  }

  test("primary-constructor-outgoing") {
    for {
      _ <- assertOutgoingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |class Foo<<>>/*1*/(x: Int)
           |
           |object Main {
           |  def fo@@o(x: Int) = new Foo<?<>?>/*1*/(x)
           |}
           |
           |""".stripMargin
      )
    } yield ()
  }

  test("secondary-constructor-incoming") {
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
           |  def <<foo>>/*1*/(x: Int, y: Int) = new Foo<?<>?>/*1*/(x, y)
           |}
           |
           |""".stripMargin
      )
    } yield ()
  }

  test("secondary-constructor-outgoing") {
    for {
      _ <- assertOutgoingCalls(
        """|/a/src/main/scala/a/Main.scala
           |package a
           |
           |class Foo(x: Int) {
           |  def <<this>>/*1*/(x: Int, y: Int) = this(x + y)
           |}
           |
           |object Main {
           |  def f@@oo(x: Int, y: Int) = new Foo<?<>?>/*1*/(x, y)
           |}
           |
           |""".stripMargin
      )
    } yield ()
  }

  test("case-class-constructor-incoming") {
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
    } yield ()
  }

  // apply call is not supported yet
  test("case-class-constructor-outgoing".ignore) {
    for {
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

  // apply call is not supported yet, MBT doesn't index synthetic apply calls
  test("apply-incoming".ignore) {
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
    } yield ()
  }

  // apply call is not supported yet, MBT doesn't index synthetic apply calls
  test("apply-outgoing".ignore) {
    for {
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

  // unapply call is not supported yet, MBT doesn't index synthetic unapply calls
  test("unapply-incoming".ignore) {
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
    } yield ()
  }

  // unapply call is not supported yet, MBT doesn't index synthetic unapply calls
  test("unapply-outgoing".ignore) {
    for {
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

  test(
    "decl-outgoing-call"
  ) { // https://github.com/scalameta/metals/issues/4489
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

  test("incoming-calls-finds-parent-calls") {
    for {
      _ <- assertIncomingCalls(
        """|/a/src/main/scala/a/Demo.scala
           |package a
           |
           |trait Service {
           |  def get(): Unit
           |}
           |
           |class Impl extends Service {
           | def g@@et(): Unit = ()
           |}
           |
           |object Demo {
           |  val s: Service = new Impl
           |  def <<main>>/*1*/() = s.<?<get>?>/*1*/()
           |}
           |
           |""".stripMargin
      )
    } yield ()
  }

}
