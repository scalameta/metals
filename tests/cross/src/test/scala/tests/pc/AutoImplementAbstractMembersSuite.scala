package tests.pc

import java.net.URI

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.TextEdits

import munit.Location
import munit.TestOptions
import org.eclipse.{lsp4j => l}
import tests.BaseCodeActionSuite

class AutoImplementAbstractMembersSuite extends BaseCodeActionSuite {

  checkEdit(
    "classdef",
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  class <<Concrete>> extends Base {
       |  }
       |}
       |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  class Concrete extends Base {
       |
       |    override def foo(x: Int): Int = ???
       |
       |    override def bar(x: String): String = ???
       |
       |  }
       |}
       |""".stripMargin,
  )

  checkEdit(
    "classdef-tparam",
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  class <<Concrete>>[T] extends Base
       |}
       |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  class Concrete[T] extends Base {
       |
       |    override def foo(x: Int): Int = ???
       |
       |    override def bar(x: String): String = ???
       |
       |  }
       |}
       |""".stripMargin,
  )

  checkEdit(
    "empty-lines-between-members",
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  class <<Concrete>> extends Base {
       |
       |    def bar(x: String): String = ???
       |
       |  }
       |}
       |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  class Concrete extends Base {
       |
       |    override def foo(x: Int): Int = ???
       |
       |
       |    def bar(x: String): String = ???
       |
       |  }
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|package a
           |
           |object A {
           |  trait Base {
           |    def foo(x: Int): Int
           |    def bar(x: String): String
           |  }
           |  class Concrete extends Base {
           |
           |
           |    override def foo(x: Int): Int = ???
           |
           |    def bar(x: String): String = ???
           |
           |  }
           |}
           |""".stripMargin
    ),
  )

  checkEdit(
    "objectdef",
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |  }
       |  object <<Concrete>> extends Base {
       |  }
       |}
       |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |  }
       |  object Concrete extends Base {
       |
       |    override def foo(x: Int): Int = ???
       |
       |  }
       |}
       |""".stripMargin,
  )

  checkEdit(
    "overload",
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  class <<Concrete>> extends Base {
       |    override def foo(x: Int): Int = x
       |  }
       |}
       |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  class Concrete extends Base {
       |
       |    override def bar(x: String): String = ???
       |
       |    override def foo(x: Int): Int = x
       |  }
       |}
       |""".stripMargin,
  )

  checkEdit(
    "braces",
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  class <<Concrete>> extends Base {}
       |}
       |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  class Concrete extends Base {
       |
       |    override def foo(x: Int): Int = ???
       |
       |    override def bar(x: String): String = ???
       |
       |  }
       |}
       |""".stripMargin,
  )

  checkEdit(
    "object-creation",
    """
      |object Main {
      |  new <<Iterable>>[Int] {}
      |}
    """.stripMargin,
    """
      |object Main {
      |  new Iterable[Int] {
      |
      |    override def iterator: Iterator[Int] = ???
      |
      |  }
      |}
      |""".stripMargin,
  )

  checkEdit(
    "context-bound",
    """
      |trait Context {
      |   def add[T:Ordering]: T
      |}
      |object Main {
      |  new <<Context>> {
      |  }
      |}
     """.stripMargin,
    """
      |trait Context {
      |   def add[T:Ordering]: T
      |}
      |object Main {
      |  new Context {
      |
      |    override def add[T: Ordering]: T = ???
      |
      |  }
      |}
      |""".stripMargin,
  )

  checkEdit(
    "generics-inheritance",
    """
      |trait Context[T] {
      |   def method: T
      |}
      |object Main {
      |  class <<Concrete>> extends Context[Int] {
      |  }
      |}
     """.stripMargin,
    """
      |trait Context[T] {
      |   def method: T
      |}
      |object Main {
      |  class Concrete extends Context[Int] {
      |
      |    override def method: Int = ???
      |
      |  }
      |}
      |""".stripMargin,
  )

  checkEdit(
    "ignore-non-abstract",
    """
      |trait Abstract extends SuperAbstract {
      |  def aaa: Int
      |  def bbb: Int = 2 // should be ignored
      |  type TypeAlias = String // should be ignored
      |}
      |object Main {
      |  new <<Abstract>> {
      |  }
      |}
     """.stripMargin,
    """
      |trait Abstract extends SuperAbstract {
      |  def aaa: Int
      |  def bbb: Int = 2 // should be ignored
      |  type TypeAlias = String // should be ignored
      |}
      |object Main {
      |  new Abstract {
      |
      |    override def aaa: Int = ???
      |
      |  }
      |}
      |""".stripMargin,
  )

  checkEdit(
    "import",
    """|abstract class Mutable {
       |  def foo: scala.collection.mutable.Set[Int]
       |  def bar: scala.collection.immutable.Set[Int]
       |}
       |object Main {
       |  new <<Mutable>> {
       |  }
       |}
       |""".stripMargin,
    """|import scala.collection.mutable
       |abstract class Mutable {
       |  def foo: scala.collection.mutable.Set[Int]
       |  def bar: scala.collection.immutable.Set[Int]
       |}
       |object Main {
       |  new Mutable {
       |
       |    override def foo: mutable.Set[Int] = ???
       |
       |    override def bar: Set[Int] = ???
       |
       |  }
       |}
       |""".stripMargin,
  )

  checkEdit(
    "nested-inheritance",
    """|abstract class SuperAbstract {
       |  def foo: Int
       |}
       |trait Bar extends SuperAbstract {
       |  def bar: Int
       |}
       |object Main {
       |  class <<Baz>> extends Bar {
       |  }
       |}
       |""".stripMargin,
    """|abstract class SuperAbstract {
       |  def foo: Int
       |}
       |trait Bar extends SuperAbstract {
       |  def bar: Int
       |}
       |object Main {
       |  class Baz extends Bar {
       |
       |    override def foo: Int = ???
       |
       |    override def bar: Int = ???
       |
       |  }
       |}
       |""".stripMargin,
  )

  checkEdit(
    "jutil",
    """|abstract class JUtil {
       |  def foo: java.util.List[Int]
       |}
       |class <<Main>> extends JUtil {
       |}
       |""".stripMargin,
    """|import java.{util => ju}
       |abstract class JUtil {
       |  def foo: java.util.List[Int]
       |}
       |class Main extends JUtil {
       |
       |  override def foo: ju.List[Int] = ???
       |
       |}
       |""".stripMargin,
  )

  checkEdit(
    "jutil-conflict",
    """|package jutil
       |abstract class JUtil {
       |  def foo: java.util.List[Int]
       |}
       |class <<Main>> extends JUtil {
       |  val java = 42
       |}
       |""".stripMargin,
    // Ensure we don't insert `_root_` prefix for import because `val java = 42` is local.
    """|package jutil
       |
       |import java.{util => ju}
       |abstract class JUtil {
       |  def foo: java.util.List[Int]
       |}
       |class Main extends JUtil {
       |
       |  override def foo: ju.List[Int] = ???
       |
       |  val java = 42
       |}
       |""".stripMargin,
  )

  checkEdit(
    "val",
    """|abstract class Abstract {
       |  val baz: String
       |}
       |class <<Main>> extends Abstract {
       |}
       |""".stripMargin,
    """|abstract class Abstract {
       |  val baz: String
       |}
       |class Main extends Abstract {
       |
       |  override val baz: String = ???
       |
       |}
       |""".stripMargin,
  )

  checkEdit(
    "indent-def",
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  // if there's a member, infer indent based on it.
       |  class <<Concrete>> extends Base {
       |       override def foo(x: Int): Int = x
       |  }
       |}
       |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  // if there's a member, infer indent based on it.
       |  class Concrete extends Base {
       |
       |       override def bar(x: String): String = ???
       |
       |       override def foo(x: Int): Int = x
       |  }
       |}
       |""".stripMargin,
  )

  checkEdit(
    "indent-val",
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  // if there's a member, infer indent based on it.
       |  class <<Concrete>> extends Base {
       |           val test = 1
       |  }
       |}
       |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  // if there's a member, infer indent based on it.
       |  class Concrete extends Base {
       |
       |           override def foo(x: Int): Int = ???
       |
       |           override def bar(x: String): String = ???
       |
       |           val test = 1
       |  }
       |}
       |""".stripMargin,
  )

  checkEdit(
    "indent-type",
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  // if there's a member, infer indent based on it.
       |  class <<Concrete>> extends Base {
       |           type T = Int
       |  }
       |}
       |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  // if there's a member, infer indent based on it.
       |  class Concrete extends Base {
       |
       |           override def foo(x: Int): Int = ???
       |
       |           override def bar(x: String): String = ???
       |
       |           type T = Int
       |  }
       |}
       |""".stripMargin,
  )

  checkEdit(
    "indent-object-creation",
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  new <<Base>> {
       |          def bar(x: String): Int = x
       |  }
       |}
       |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  new Base {
       |
       |          override def foo(x: Int): Int = ???
       |
       |          def bar(x: String): Int = x
       |  }
       |}
       |""".stripMargin,
  )

  checkEdit(
    "infer-indent-constructor",
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  <<class>> Concrete(num: Int) extends Base {
       |  }
       |}
       |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  class Concrete(num: Int) extends Base {
       |
       |    override def foo(x: Int): Int = ???
       |
       |    override def bar(x: String): String = ???
       |
       |  }
       |}
       |""".stripMargin,
  )

  checkEdit(
    "infer-indent-auxiliary-constructor",
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  <<class>> Concrete(num: Int) extends Base {
       |      def this() = { this(4) }
       |  }
       |}
       |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  class Concrete(num: Int) extends Base {
       |
       |      override def foo(x: Int): Int = ???
       |
       |      override def bar(x: String): String = ???
       |
       |      def this() = { this(4) }
       |  }
       |}
       |""".stripMargin,
  )

  checkEdit(
    "indent-closing-brace",
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |  }
       |      new <<Base>> {}
       |}
       |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |  }
       |      new Base {
       |
       |        override def foo(x: Int): Int = ???
       |
       |      }
       |}
       |""".stripMargin,
  )

  checkEdit(
    "complete-braces-moduledef",
    """|package a
       |
       |trait Base {
       |  def foo(x: Int): Int
       |}
       |object <<Concrete>> extends Base
       |""".stripMargin,
    """|package a
       |
       |trait Base {
       |  def foo(x: Int): Int
       |}
       |object Concrete extends Base {
       |
       |  override def foo(x: Int): Int = ???
       |
       |}
       |""".stripMargin,
  )

  checkEdit(
    "access-modifiers",
    """|package a
       |
       |trait Base {
       |  // private is not available for abstract members
       |  protected[a] def f(): Unit
       |  protected def d(): Unit
       |  protected[a] val s: Unit
       |  implicit val a: String
       |  // lazy values might not be abstract
       |}
       |object Test {
       |   class <<Concrete>> extends Base
       |}
       |""".stripMargin,
    """|package a
       |
       |trait Base {
       |  // private is not available for abstract members
       |  protected[a] def f(): Unit
       |  protected def d(): Unit
       |  protected[a] val s: Unit
       |  implicit val a: String
       |  // lazy values might not be abstract
       |}
       |object Test {
       |   class Concrete extends Base {
       |
       |     override protected[a] def f(): Unit = ???
       |
       |     override protected def d(): Unit = ???
       |
       |     override protected[a] val s: Unit = ???
       |
       |     override implicit val a: String = ???
       |
       |   }
       |}
       |""".stripMargin,
  )

  checkEdit(
    "complete-braces-indent",
    """|package a
       |
       |trait Base {
       |  def foo(x: Int): Int
       |}
       |object Test {
       |   class <<Concrete>> extends Base
       |}
       |""".stripMargin,
    """|package a
       |
       |trait Base {
       |  def foo(x: Int): Int
       |}
       |object Test {
       |   class Concrete extends Base {
       |
       |     override def foo(x: Int): Int = ???
       |
       |   }
       |}
       |""".stripMargin,
  )

  checkEdit(
    "selftype",
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  class <<Concrete>> extends Base { self =>
       |  }
       |}
       |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  class Concrete extends Base { self =>
       |
       |    override def foo(x: Int): Int = ???
       |
       |    override def bar(x: String): String = ???
       |
       |  }
       |}
       |""".stripMargin,
  )

  checkEdit(
    "selftype-arrow",
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  class <<Concrete>> extends Base { // > reference
       |    self =>
       |  }
       |}
       |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  class Concrete extends Base { // > reference
       |    self =>
       |
       |    override def foo(x: Int): Int = ???
       |
       |    override def bar(x: String): String = ???
       |
       |  }
       |}
       |""".stripMargin,
  )

  checkEdit(
    "tab-indented1",
    """|package a
       |
       |object A {
       |	trait Base {
       |		def foo(x: Int): Int
       |		def bar(x: String): String
       |	}
       |	class <<Concrete>> extends Base {
       |	}
       |}
       |""".stripMargin,
    """|package a
       |
       |object A {
       |	trait Base {
       |		def foo(x: Int): Int
       |		def bar(x: String): String
       |	}
       |	class Concrete extends Base {
       |
       |		override def foo(x: Int): Int = ???
       |
       |		override def bar(x: String): String = ???
       |
       |	}
       |}
       |""".stripMargin,
  )

  checkEdit(
    "tab-indented2",
    """|package a
       |
       |object A {
       |	trait Base {
       |		def foo(x: Int): Int
       |		def bar(x: String): String
       |	}
       |	class <<Concrete>> extends Base
       |}
       |""".stripMargin,
    """|package a
       |
       |object A {
       |	trait Base {
       |		def foo(x: Int): Int
       |		def bar(x: String): String
       |	}
       |	class Concrete extends Base {
       |
       |		override def foo(x: Int): Int = ???
       |
       |		override def bar(x: String): String = ???
       |
       |	}
       |}
       |""".stripMargin,
  )

  checkEdit(
    "braceless-basic".tag(IgnoreScala2),
    """|package a
       |
       |object A {
       |  trait Base:
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |
       |  class <<Concrete>> extends Base:
       |    def foo(x: Int): Int = x
       |
       |}
       |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Base:
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |
       |  class Concrete extends Base:
       |
       |    override def bar(x: String): String = ???
       |
       |    def foo(x: Int): Int = x
       |
       |}
       |""".stripMargin,
  )

  checkEdit(
    "braceless-selftype".tag(IgnoreScala2),
    """|package a
       |
       |object A {
       |  trait Base:
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |
       |  class <<Concrete>> extends Base:
       |    def foo(x: Int): Int = x
       |
       |}
       |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Base:
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |
       |  class Concrete extends Base:
       |
       |    override def bar(x: String): String = ???
       |
       |    def foo(x: Int): Int = x
       |
       |}
       |""".stripMargin,
  )

  checkEdit(
    "tab-indented-braceless".tag(IgnoreScala2),
    """|package a
       |
       |trait Base:
       |	def foo(x: Int): Int
       |	def bar(x: String): String
       |
       |class <<Concrete>> extends Base:
       |	def foo(x: Int): Int = x
       |""".stripMargin,
    """|package a
       |
       |trait Base:
       |	def foo(x: Int): Int
       |	def bar(x: String): String
       |
       |class Concrete extends Base:
       |
       |	override def bar(x: String): String = ???
       |
       |	def foo(x: Int): Int = x
       |
       |""".stripMargin,
  )

  checkEdit(
    "extension-methods".tag(IgnoreScala2),
    """|package a
       |
       |trait Base:
       |  extension (x: Int)
       |    def foo: Int
       |    def bar: String
       |
       |class <<Concrete>> extends Base
       |""".stripMargin,
    """|package a
       |
       |trait Base:
       |  extension (x: Int)
       |    def foo: Int
       |    def bar: String
       |
       |class Concrete extends Base {
       |
       |  extension (x: Int) override def foo: Int = ???
       |
       |  extension (x: Int) override def bar: String = ???
       |
       |}
       |""".stripMargin,
  )

  checkEdit(
    "extension-methods-tparam".tag(IgnoreScala2),
    """|package a
       |
       |trait Base[T]:
       |  extension (x: T)
       |    def foo: Int
       |    def bar: String
       |
       |class <<Concrete>>[T] extends Base[Int]
       |""".stripMargin,
    """|package a
       |
       |trait Base[T]:
       |  extension (x: T)
       |    def foo: Int
       |    def bar: String
       |
       |class Concrete[T] extends Base[Int] {
       |
       |  extension (x: Int) override def foo: Int = ???
       |
       |  extension (x: Int) override def bar: String = ???
       |
       |}
       |""".stripMargin,
  )

  checkEdit(
    "given-object-creation".tag(IgnoreScala2),
    """|package given
       |
       |trait Foo:
       |  def foo(x: Int): Int
       |  def bar(x: String): String
       |
       |given <<Foo>> with
       |  def foo(x: Int): Int = x
       |""".stripMargin,
    """|package given
       |
       |trait Foo:
       |  def foo(x: Int): Int
       |  def bar(x: String): String
       |
       |given Foo with
       |
       |  override def bar(x: String): String = ???
       |
       |  def foo(x: Int): Int = x
       |""".stripMargin,
  )

  checkEdit(
    "given-object-creation-braces".tag(IgnoreScala2),
    """|package given
       |
       |trait Foo:
       |  def foo(x: Int): Int
       |  def bar(x: String): String
       |
       |given <<Foo>> with {}
       |""".stripMargin,
    """|package given
       |
       |trait Foo:
       |  def foo(x: Int): Int
       |  def bar(x: String): String
       |
       |given Foo with {
       |
       |  override def foo(x: Int): Int = ???
       |
       |  override def bar(x: String): String = ???
       |
       |}
       |""".stripMargin,
  )

  checkEdit(
    "given-object-with".tag(IgnoreScala2),
    """|package given
       |
       |trait Foo:
       |  def foo(x: Int): Int
       |  def bar(x: String): String
       |
       |given <<Foo>>
       |""".stripMargin,
    """|package given
       |
       |trait Foo:
       |  def foo(x: Int): Int
       |  def bar(x: String): String
       |
       |given Foo with
       |
       |  override def foo(x: Int): Int = ???
       |
       |  override def bar(x: String): String = ???
       |""".stripMargin,
  )

  checkEdit(
    "type-alias",
    """|package example
       |
       |trait NodeDb {
       |  type N
       |  def method(node: N): String
       |}
       |
       |class <<InMemoryNodeDb>> extends NodeDb
       |""".stripMargin,
    """|package example
       |
       |trait NodeDb {
       |  type N
       |  def method(node: N): String
       |}
       |
       |class InMemoryNodeDb extends NodeDb {
       |
       |  override def method(node: N): String = ???
       |
       |}
       |""".stripMargin,
  )

  checkEdit(
    "higher-kind-type".tag(IgnoreScala2),
    """|package example
       |
       |trait NodeDb[F[_]]:
       |  type N
       |
       |  extension (node: N)
       |    def leftChild: F[Option[N]]
       |    def rightChild: F[Option[N]]
       |
       |class <<InMemoryNodeDb>>[F[_]] extends NodeDb[F]
       |""".stripMargin,
    """|package example
       |
       |trait NodeDb[F[_]]:
       |  type N
       |
       |  extension (node: N)
       |    def leftChild: F[Option[N]]
       |    def rightChild: F[Option[N]]
       |
       |class InMemoryNodeDb[F[_]] extends NodeDb[F] {
       |
       |  extension (node: N) override def leftChild: F[Option[N]] = ???
       |
       |  extension (node: N) override def rightChild: F[Option[N]] = ???
       |
       |}
       |""".stripMargin,
  )

  checkEdit(
    "path-dependent-type-arg".tag(IgnoreScala2),
    """|package a
       |import scala.deriving.Mirror
       |trait Foo:
       |  def foo[A](using mirror: Mirror.ProductOf[A])(ordering: Ordering[mirror.MirroredElemTypes]): Unit
       |
       |class <<Bar>> extends Foo
       |""".stripMargin,
    """|package a
       |import scala.deriving.Mirror
       |import scala.deriving.Mirror.ProductOf
       |trait Foo:
       |  def foo[A](using mirror: Mirror.ProductOf[A])(ordering: Ordering[mirror.MirroredElemTypes]): Unit
       |
       |class Bar extends Foo {
       |
       |  override def foo[A](using mirror: ProductOf[A])(ordering: Ordering[mirror.MirroredElemTypes]): Unit = ???
       |
       |}
       |""".stripMargin,
  )

  def checkEdit(
      name: TestOptions,
      original: String,
      expected: String,
      compat: Map[String, String] = Map.empty,
  )(implicit loc: Location): Unit =
    test(name) {
      val edits = getAutoImplement(original)
      if (edits.isEmpty) fail("obtained no edits")
      val (code, _, _) = params(original)
      val obtained = TextEdits.applyEdits(code, edits)
      assertNoDiff(
        obtained,
        getExpected(expected, compat, scalaVersion),
      )
    }

  def getAutoImplement(
      original: String,
      filename: String = "A.scala",
  ): List[l.TextEdit] = {
    val (code, _, offset) = params(original)
    val result = presentationCompiler
      .implementAbstractMembers(
        CompilerOffsetParams(URI.create(filename), code, offset, cancelToken)
      )
      .get()
    result.asScala.toList
  }

}
