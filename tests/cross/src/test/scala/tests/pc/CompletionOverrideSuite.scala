package tests.pc

import tests.BaseCompletionSuite

class CompletionOverrideSuite extends BaseCompletionSuite {

  override def requiresJdkSources: Boolean = true

  override def requiresScalaLibrarySources: Boolean = true

  checkEdit(
    "basic",
    """
      |object Main extends AutoCloseable {
      |  def close@@
      |}
    """.stripMargin,
    """
      |object Main extends AutoCloseable {
      |  def close(): Unit = ${0:???}
      |}
      |""".stripMargin,
  )

  checkEdit(
    "overload",
    """
      |trait Interface {
      |  def foo(a: Int): Unit
      |  def foo(a: String): Unit
      |}
      |object Main extends Interface {
      |  override def foo(a: Int): Unit = ()
      |  override def foo@@
      |}
    """.stripMargin,
    """
      |trait Interface {
      |  def foo(a: Int): Unit
      |  def foo(a: String): Unit
      |}
      |object Main extends Interface {
      |  override def foo(a: Int): Unit = ()
      |  override def foo(a: String): Unit = ${0:???}
      |}
      |""".stripMargin,
  )

  checkEdit(
    "seen-from",
    """
      |object Main {
      |  new Iterable[Int] {
      |    def iterato@@
      |  }
      |}
    """.stripMargin,
    """
      |object Main {
      |  new Iterable[Int] {
      |    def iterator: Iterator[Int] = ${0:???}
      |  }
      |}
      |""".stripMargin,
    filter = _.contains("iterat"),
  )

  checkEdit(
    "generic",
    """
      |object Main {
      |  new scala.Traversable[Int] {
      |    def foreach@@
      |  }
      |}
    """.stripMargin,
    """
      |object Main {
      |  new scala.Traversable[Int] {
      |    override def foreach[U](f: Int => U): Unit = ${0:???}
      |  }
      |}
      |""".stripMargin,
    compat = Map(
      "2.12" ->
        """
          |object Main {
          |  new scala.Traversable[Int] {
          |    def foreach[U](f: Int => U): Unit = ${0:???}
          |  }
          |}
          |""".stripMargin
    ),
  )

  checkEdit(
    "context-bound",
    """
      |trait Context {
      |   def add[T:Ordering]: T
      |}
      |object Main {
      |  new Context {
      |    override def add@@
      |  }
      |}
    """.stripMargin,
    """
      |trait Context {
      |   def add[T:Ordering]: T
      |}
      |object Main {
      |  new Context {
      |    override def add[T: Ordering]: T = ${0:???}
      |  }
      |}
      |""".stripMargin,
  )

  checkEdit(
    "import", // import position is flaky
    """
      |object Main {
      |  new java.nio.file.SimpleFileVisitor[java.nio.file.Path] {
      |    def visitFil@@
      |  }
      |}
    """.stripMargin,
    """
      |import java.nio.file.{FileVisitResult, Path}
      |import java.nio.file.attribute.BasicFileAttributes
      |
      |object Main {
      |  new java.nio.file.SimpleFileVisitor[java.nio.file.Path] {
      |    override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = ${0:???}
      |  }
      |}
      |""".stripMargin,
    assertSingleItem = false,
    compat = Map(
      "3" ->
        """|import java.nio.file.attribute.BasicFileAttributes
           |import java.nio.file.FileVisitResult
           |import java.nio.file.Path
           |
           |object Main {
           |  new java.nio.file.SimpleFileVisitor[java.nio.file.Path] {
           |    override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = ${0:???}
           |  }
           |}
           |""".stripMargin
    ),
  )

  check(
    "empty",
    """
      |trait SuperAbstract {
      |  def aaa: Int = 2
      |}
      |trait Abstract extends SuperAbstract {
      |  def bbb: Int = 2
      |  type TypeAlias = String // should be ignored
      |}
      |object Main {
      |  new Abstract {
      |    def @@
      |  }
      |}
    """.stripMargin,
    // assert that `isInstanceOf` and friends are not included
    """|override def aaa: Int
       |override def bbb: Int
       |override def equals(obj: Any): Boolean
       |override def hashCode(): Int
       |override def toString(): String
       |override def clone(): Object
       |override def finalize(): Unit
       |""".stripMargin,
    includeDetail = false,
    compat = Map(
      "3" ->
        """|override def aaa: Int
           |override def bbb: Int
           |override def equals(x$0: Any): Boolean
           |override def hashCode(): Int
           |override def toString(): String
           |""".stripMargin
    ),
  )

  def implement(completion: String): String =
    s"""
       |trait Abstract {
       |  def implementMe: Int
       |}
       |object Main {
       |  new Abstract {
       |    $completion
       |  }
       |}
    """.stripMargin

  checkEdit(
    "implement",
    // assert that `override` is not inserted.
    implement("def implement@@"),
    implement("def implementMe: Int = ${0:???}"),
  )
  checkEdit(
    "implement-override",
    // assert that `override` is inserted.
    implement("override def implement@@"),
    implement("override def implementMe: Int = ${0:???}"),
  )

  checkEdit(
    "error",
    """
      |object Main {
      |  new scala.Iterable[Unknown] {
      |    def iterato@@
      |  }
      |}
    """.stripMargin,
    // Replace error types with type parameter name `A`. IntelliJ converts the unknown type
    // into `Any`, which is less helpful IMO.
    """
      |object Main {
      |  new scala.Iterable[Unknown] {
      |    def iterator: Iterator[A] = ${0:???}
      |  }
      |}
    """.stripMargin,
    filter = _.contains("iterat"),
  )

  check(
    "sort",
    """
      |trait Super {
      |  def a: Int = 2
      |  def b: Int
      |}
      |object Main {
      |  new Super {
      |    def @@
      |  }
      |}
    """.stripMargin,
    // assert that `isInstanceOf` and friends are not included
    """|def b: Int
       |override def a: Int
       |""".stripMargin,
    topLines = Some(2),
    includeDetail = false,
  )

  checkEdit(
    "conflict",
    s"""package a.b
       |abstract class Conflict {
       |  def self: Conflict
       |}
       |object Main {
       |  class Conflict
       |  new a.b.Conflict {
       |    def self@@
       |  }
       |}
       |""".stripMargin,
    """|package a.b
       |
       |import a.b
       |abstract class Conflict {
       |  def self: Conflict
       |}
       |object Main {
       |  class Conflict
       |  new a.b.Conflict {
       |    def self: b.Conflict = ${0:???}
       |  }
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|package a.b
           |
           |import a.b
           |abstract class Conflict {
           |  def self: Conflict
           |}
           |object Main {
           |  class Conflict
           |  new a.b.Conflict {
           |    def self: a.b.Conflict = ${0:???}
           |  }
           |}
           |""".stripMargin
    ),
  )

  check(
    "conflict2",
    s"""package a.c
       |abstract class Conflict {
       |  type Inner
       |  def self: Conflict
       |  def selfArg: Option[Conflict]
       |  def selfPath: Conflict#Inner
       |}
       |object Main {
       |  class Conflict
       |  val a = 2
       |  new _root_.a.c.Conflict {
       |    def self@@
       |  }
       |}
       |""".stripMargin,
    """|def self: c.Conflict
       |def selfArg: Option[c.Conflict]
       |def selfPath: Inner
       |Implement all members
       |""".stripMargin,
    includeDetail = false,
    compat = Map(
      "3" ->
        """|def self: a.c.Conflict
           |def selfArg: Option[a.c.Conflict]
           |def selfPath: Inner
           |""".stripMargin
    ),
  )

  checkEdit(
    "mutable",
    """|abstract class Mutable {
       |  def foo: scala.collection.mutable.Set[Int]
       |}
       |object Main {
       |  new Mutable {
       |    def foo@@
       |  }
       |}
       |""".stripMargin,
    """|import scala.collection.mutable
       |abstract class Mutable {
       |  def foo: scala.collection.mutable.Set[Int]
       |}
       |object Main {
       |  new Mutable {
       |    def foo: mutable.Set[Int] = ${0:???}
       |  }
       |}
       |""".stripMargin,
  )

  checkEditLine(
    "mutable-conflict",
    s"""|abstract class Mutable {
        |  def foo: scala.collection.mutable.Set[Int]
        |}
        |object Main {
        |  new Mutable {
        |    val mutable = 42
        |___
        |  }
        |}
        |""".stripMargin,
    "    def foo@@",
    """    def foo: scala.collection.mutable.Set[Int] = ${0:???}""",
    compat = Map(
      "3" ->
        """    def foo: collection.mutable.Set[Int] = ${0:???}"""
    ),
  )

  checkEdit(
    "jutil",
    """|abstract class JUtil {
       |  def foo: java.util.List[Int]
       |}
       |class Main extends JUtil {
       |  def foo@@
       |}
       |""".stripMargin,
    """|import java.{util => ju}
       |abstract class JUtil {
       |  def foo: java.util.List[Int]
       |}
       |class Main extends JUtil {
       |  def foo: ju.List[Int] = ${0:???}
       |}
       |""".stripMargin,
  )

  checkEdit(
    "jutil-conflict",
    """|package jutil
       |abstract class JUtil {
       |  def foo: java.util.List[Int]
       |}
       |class Main extends JUtil {
       |  val java = 42
       |  def foo@@
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
       |  val java = 42
       |  def foo: ju.List[Int] = ${0:???}
       |}
       |""".stripMargin,
  )

  checkEditLine(
    "jutil-conflict2",
    s"""|package jutil2
        |abstract class JUtil {
        |  def foo: java.util.List[Int]
        |}
        |class Main extends JUtil {
        |  val ju = 42
        |___
        |}
        |""".stripMargin,
    "  def foo@@",
    // Can't use `import java.{util => ju}` because `val ju = 42` is in scope.
    """  def foo: java.util.List[Int] = ${0:???}""",
  )

  checkEdit(
    "jlang",
    """|abstract class Mutable {
       |  def foo: java.lang.StringBuilder
       |}
       |class Main extends Mutable {
       |  def foo@@
       |}
       |""".stripMargin,
    """|import java.lang
       |abstract class Mutable {
       |  def foo: java.lang.StringBuilder
       |}
       |class Main extends Mutable {
       |  def foo: lang.StringBuilder = ${0:???}
       |}
       |""".stripMargin,
  )

  checkEditLine(
    "alias",
    s"""|
        |abstract class Abstract {
        |  def foo: scala.collection.immutable.List[Int]
        |}
        |class Main extends Abstract {
        |  ___
        |}
        |
        |""".stripMargin,
    "  def foo@@",
    """  def foo: List[Int] = ${0:???}""".stripMargin,
  )

  checkEditLine(
    "alias2",
    s"""|package alias
        |abstract class Alias {
        |  type Foobar = List[Int]
        |  def foo: Foobar
        |}
        |class Main extends Alias {
        |  ___
        |}
        |
        |""".stripMargin,
    "  def foo@@",
    // NOTE(olafur) I am not sure this is desirable behavior, we might want to
    // consider not dealiasing here.
    """  def foo: List[Int] = ${0:???}""".stripMargin,
    compat = Map(
      "3" ->
        """  def foo: Foobar = ${0:???}""".stripMargin
    ),
  )

  checkEditLine(
    "rename",
    s"""|import java.lang.{Boolean => JBoolean}
        |abstract class Abstract {
        |  def foo: JBoolean
        |}
        |class Main extends Abstract {
        |___
        |}
        |
        |""".stripMargin,
    "  def foo@@",
    """  def foo: JBoolean = ${0:???}""".stripMargin,
  )

  checkEditLine(
    "path",
    s"""|package path
        |abstract class Path {
        |  type Out
        |  def foo: Out
        |}
        |class Main extends Path {
        |___
        |}
        |
        |""".stripMargin,
    "  def foo@@",
    """  def foo: Out = ${0:???}""".stripMargin,
  )

  checkEditLine(
    "path-alias",
    s"""|package paththis
        |abstract class Path {
        |  type Out
        |  def foo: Out
        |}
        |class Main extends Path {
        |  type Out = String
        |___
        |}
        |
        |""".stripMargin,
    "  def foo@@",
    """  def foo: String = ${0:???}""".stripMargin,
    compat = Map(
      "3" ->
        """  def foo: Out = ${0:???}""".stripMargin
    ),
  )

  checkEditLine(
    "path-this",
    """|package paththis
       |abstract class Path {
       |  type Out
       |}
       |class Main extends Path {
       |  trait Conflict {
       |    def conflict: Out
       |  }
       |  object Conflict extends Conflict {
       |    type Out = Int
       |___
       |  }
       |}
       |""".stripMargin,
    "    def conflict@@",
    """    def conflict: Main.this.Out = ${0:???}""".stripMargin,
  )

  check(
    "final",
    """|package f
       |abstract class Final {
       |  def hello1: Int = 42
       |  final def hello2: Int = 42
       |}
       |class Main extends Final {
       |  def hello@@
       |}
       |""".stripMargin,
    """override def hello1: Int
      |""".stripMargin,
    includeDetail = false,
  )

  check(
    "default",
    """|package g
       |abstract class Final {
       |  def hello1: Int
       |  final def hello2(hello2: Int = 42)
       |}
       |class Main extends Final {
       |  def hello@@
       |}
       |""".stripMargin,
    """def hello1: Int
      |""".stripMargin,
    includeDetail = false,
  )

  checkEditLine(
    "default2",
    """|package h
       |abstract class Final {
       |  def hello(arg: Int = 42): Unit
       |}
       |class Main extends Final {
       |  ___
       |}
       |""".stripMargin,
    "def hello@@",
    """def hello(arg: Int): Unit = ${0:???}""".stripMargin,
  )

  checkEditLine(
    "existential",
    """|package i
       |abstract class Exist {
       |  def exist: Set[_]
       |}
       |class Main extends Exist {
       |  ___
       |}
       |""".stripMargin,
    "def exist@@",
    """def exist: Set[_] = ${0:???}""".stripMargin,
    compat = Map(
      "3" ->
        """def exist: Set[?] = ${0:???}""".stripMargin
    ),
  )

  checkEditLine(
    "cake",
    """|package i
       |trait Trees { this: Global =>
       |  case class Tree()
       |  def Apply(tree: Tree): Tree = ???
       |}
       |class Global extends Trees  {
       |  ___
       |}
       |""".stripMargin,
    "def Apply@@",
    """override def Apply(tree: Tree): Tree = ${0:???}""".stripMargin,
  )

  checkEditLine(
    "cake2",
    """|package i2
       |trait Trees { this: Global =>
       |  case class Tree()
       |  def Apply(tree: Tree): Tree = ???
       |}
       |class Global extends Trees  {
       |}
       |class MyGlobal extends Global  {
       |  ___
       |}
       |""".stripMargin,
    "def Apply@@",
    """override def Apply(tree: Tree): Tree = ${0:???}""".stripMargin,
  )

  checkEditLine(
    "cake-generic",
    """|package i
       |trait Trees[T] { this: Global =>
       |  case class Tree()
       |  def Apply(tree: T): Tree = ???
       |}
       |class Global extends Trees[Int] {
       |  ___
       |}
       |""".stripMargin,
    "def Apply@@",
    """override def Apply(tree: Int): Tree = ${0:???}""".stripMargin,
  )

  check(
    "val-negative",
    """|package j
       |abstract class Val {
       |  val hello1: Int = 42
       |  var hello2: Int = 42
       |}
       |class Main extends Val {
       |  def hello@@
       |}
       |""".stripMargin,
    "",
  )

  checkEditLine(
    "val",
    """|package k
       |abstract class Val {
       |  val hello1: Int = 42
       |}
       |class Main extends Val {
       |  ___
       |}
       |""".stripMargin,
    "val hello@@",
    "override val hello1: Int = ${0:???}",
  )

  check(
    "var",
    """|package l
       |abstract class Val {
       |  var hello1: Int = 42
       |}
       |class Main extends Val {
       |   override var hello@@
       |}
       |""".stripMargin,
    // NOTE(olafur) assert completion items are empty because it's not possible to
    // override concrete vars.
    "",
  )

  check(
    "val-var",
    """|package m
       |abstract class Val {
       |  var hello1: Int = 42
       |}
       |class Main extends Val {
       |   override val hello@@
       |}
       |""".stripMargin,
    // NOTE(olafur) assert completion items are empty because it's not possible to
    // override concrete vars.
    "",
  )

  check(
    "abstract-var",
    """|package m
       |abstract class Val {
       |  var hello1: Int
       |}
       |class Main extends Val {
       |  override var hello@@
       |}
       |""".stripMargin,
    "var hello1: Int",
    includeDetail = false,
    filterText =
      if (scalaVersion.startsWith("3")) "override var hello1: Int"
      else
        "override var hello1",
    compat = Map(
      "3" ->
        "override var hello1: Int"
    ),
  )

  check(
    "abstract-var-val",
    """|package m
       |abstract class Val {
       |  var hello1: Int
       |  val hello2: Int
       |  def hello3: Int
       |}
       |class Main extends Val {
       |  override val hello@@
       }
       |""".stripMargin,
    // NOTE(gabro): we inlcude also var and def completions despite the user typed a val
    """|var hello1: Int
       |val hello2: Int
       |def hello3: Int
       |Implement all members
       |""".stripMargin,
    includeDetail = false,
    compat = Map(
      "3" ->
        """|override var hello1: Int
           |override val hello2: Int
           |override def hello3: Int
           |""".stripMargin
    ),
  )

  check(
    "abstract-var-def",
    """|package m
       |abstract class Val {
       |  var hello1: Int
       |  val hello2: Int
       |  def hello3: Int
       |}
       |class Main extends Val {
       |  override def hello@@
       }
       |""".stripMargin,
    "def hello3: Int",
    compat = Map(
      "3" ->
        "override def hello3: Int"
    ),
  )

  check(
    "private",
    """|package n
       |abstract class Val {
       |  private def hello: Int = 2
       |}
       |class Main extends Val {
       |   override val hello@@
       |}
       |""".stripMargin,
    "",
  )

  check(
    "protected",
    """|package o
       |abstract class Val {
       |  protected def hello: Int = 2
       |}
       |class Main extends Val {
       |   override def hello@@
       |}
       |""".stripMargin,
    "override def hello: Int",
    includeDetail = false,
    compat = Map(
      "3" -> "override protected def hello: Int"
    ),
  )

  check(
    "filter",
    """|package p
       |abstract class Val {
       |  def hello: Int = 2
       |}
       |class Main extends Val {
       |   override def hel@@
       |}
       |""".stripMargin,
    "override def hello: Int",
    includeDetail = false,
    filterText =
      if (scalaVersion.startsWith("3")) "override def hello: Int"
      else
        "override def hello",
  )

  checkEditLine(
    "lazy",
    """|package q
       |abstract class Val {
       |  lazy val hello: Int = 2
       |}
       |class Main extends Val {
       |   ___
       |}
       |""".stripMargin,
    "override val hel@@",
    "override lazy val hello: Int = ${0:???}",
  )

  checkEditLine(
    "early-init",
    """|package r
       |abstract class Global {
       |  lazy val analyzer = new {
       |    val global: Global.this.type = Global.this
       |  }
       |}
       |class Main extends Global {
       |   ___
       |}
       |""".stripMargin,
    "val analyz@@",
    "override lazy val analyzer: Object{val global: r.Main} = ${0:???}",
    compat = Map(
      "3" ->
        "override lazy val analyzer: Object = ${0:???}"
    ),
  )

  checkEditLine(
    "val-trait",
    """|package s
       |trait Val {
       |  val hello1: Int = 42
       |}
       |class Main extends Val {
       |  ___
       |}
       |""".stripMargin,
    "val hello@@",
    "override val hello1: Int = ${0:???}",
  )

  check(
    "ident",
    """|package t
       |abstract class Val {
       |  def hello: Int = 2
       |}
       |class Main extends Val {
       |   hello@@
       |}
       |""".stripMargin,
    """|hello: Int
       |override def hello: Int
       |""".stripMargin,
    includeDetail = false,
  )

  check(
    "override-abstract",
    """|package u
       |abstract class Val {
       |  def overTop: Int
       |}
       |class Main extends Val {
       |   over@@
       |}
       |""".stripMargin,
    /**
     * NOTE(tgodzik) observe that the "override" is not needed here but the completion
     * has "override" because the identifier name starts with "o". It's a known limitation
     * due to the feature that allows for easily auto-completing on writing o... .
     */
    """|def overTop: Int
       |overTop: Int
       |""".stripMargin,
    includeDetail = false,
    topLines = Some(2),
    compat = Map(
      "3" ->
        """|override def overTop: Int
           |overTop: Int
           |""".stripMargin
    ),
  )

  check(
    "override-concrete",
    """|package w
       |abstract class Val {
       |  def overTop: Int = 5
       |}
       |class Main extends Val {
       |   over@@
       |}
       |""".stripMargin,
    """|overTop: Int
       |override def overTop: Int
       |""".stripMargin,
    includeDetail = false,
    topLines = Some(2),
  )

  checkEdit(
    "fuzzy-abstract".tag(IgnoreScala3),
    """|package v
       |abstract class Val {
       |  def hello: Int
       |}
       |class Main extends Val {
       |   ovhello@@
       |}
       |""".stripMargin,
    """|package v
       |abstract class Val {
       |  def hello: Int
       |}
       |class Main extends Val {
       |   override def hello: Int = ${0:???}
       |}
       |""".stripMargin,
  )

  check(
    "override-word",
    """|package y
       |abstract class Val {
       |  def hello1: Int = 2
       |  val hello2: Int = 2
       |}
       |class Main extends Val {
       |   overr@@
       |}
       |""".stripMargin,
    """|override def hello1: Int
       |override val hello2: Int
       |""".stripMargin,
    includeDetail = false,
    topLines = Some(2),
    compat = Map(
      "3" ->
        """|override def hello1: Int
           |override val hello2: Int
           |""".stripMargin
    ),
  )

  check(
    "def-word",
    """|package z
       |abstract class Val {
       |  def hello1: Int
       |  val hello2: Int = 5
       |}
       |class Main extends Val {
       |   def@@
       |}
       |""".stripMargin,
    """|def hello1: Int
       |def
       |override def equals(obj: Any): Boolean
       |""".stripMargin,
    includeDetail = false,
    topLines = Some(3),
    compat = Map(
      "3" ->
        """|def hello1: Int
           |override val hello2: Int
           |override def equals(x$0: Any): Boolean
           |""".stripMargin
    ),
  )

  checkEdit(
    "path-dependent",
    """|trait Over {
       |  object Outer {
       |    class Inner
       |  }
       |  def inner: Outer.Inner
       |}
       |class Under extends Over {
       |  def inner@@
       |}
       |""".stripMargin,
    """|trait Over {
       |  object Outer {
       |    class Inner
       |  }
       |  def inner: Outer.Inner
       |}
       |class Under extends Over {
       |  def inner: Outer.Inner = ${0:???}
       |}
       |""".stripMargin,
  )

  checkEdit(
    "overriden-twice",
    """
      |trait A {
      |  def close: Unit
      |}
      |trait B extends A{
      |  override def close : Unit = {}
      |}
      |class C extends B{
      |  close@@
      |}
    """.stripMargin,
    """|trait A {
       |  def close: Unit
       |}
       |trait B extends A{
       |  override def close : Unit = {}
       |}
       |class C extends B{
       |  override def close: Unit = ${0:???}
       |}
       |""".stripMargin,
    filter = (str) => str.contains("def"),
  )

  checkEdit(
    "not-complete-ident",
    """
      |trait A {
      |  def close: Unit
      |}
      |trait B extends A{
      |  override def close : Unit = {}
      |}
      |class C extends B{
      |  clos@@
      |}
      """.stripMargin,
    """|trait A {
       |  def close: Unit
       |}
       |trait B extends A{
       |  override def close : Unit = {}
       |}
       |class C extends B{
       |  override def close: Unit = ${0:???}
       |}
       |""".stripMargin,
    filter = (str) => str.contains("def"),
  )

  checkEdit(
    "extension-override".tag(IgnoreScala2),
    """|package a
       |
       |trait Base:
       |  extension (x: Int)
       |    def foo: Int
       |
       |class Concrete extends Base:
       |  over@@
       |""".stripMargin,
    """|package a
       |
       |trait Base:
       |  extension (x: Int)
       |    def foo: Int
       |
       |class Concrete extends Base:
       |  extension (x: Int) override def foo: Int = ${0:???}
       |""".stripMargin,
    filter = (str) => str.contains("foo"),
  )

  checkEdit(
    "extension".tag(IgnoreScala2),
    """|package a
       |
       |trait Base:
       |  extension (x: Int)
       |    def foo: Int
       |
       |class Concrete extends Base:
       |  def fo@@
       |""".stripMargin,
    """|package a
       |
       |trait Base:
       |  extension (x: Int)
       |    def foo: Int
       |
       |class Concrete extends Base:
       |  extension (x: Int) def foo: Int = ${0:???}
       |""".stripMargin,
  )

}
