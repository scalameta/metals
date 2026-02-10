package com.google.turbine.scalagen

import com.google.common.collect.ImmutableList
import com.google.turbine.diag.SourceFile
import com.google.turbine.options.LanguageVersion
import com.google.turbine.scalaparse.ScalaParser
import munit.FunSuite
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import scala.collection.mutable

class ScalaLowerSuite extends FunSuite {
  test("case-class-and-object") {
    val source =
      List(
        "package foo",
        "case class Box(val x: Int, y: String)",
        "object Box {",
        "  def apply(x: Int): Box = new Box(x, \"\")",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    assert(classes.containsKey("foo/Box"))
    assert(classes.containsKey("foo/Box$"))

    val box = readMembers(classes.get("foo/Box"))
    assert(box.methods.contains("<init>(ILjava/lang/String;)V"))
    assert(box.methods.contains("x()I"))
    assert(box.methods.contains("apply(I)Lfoo/Box;"))
    assert((box.methods("apply(I)Lfoo/Box;") & Opcodes.ACC_STATIC) != 0)

    val boxModule = readMembers(classes.get("foo/Box$"))
    assert(boxModule.fields.contains("MODULE$Lfoo/Box$;"))
    assert(boxModule.methods.contains("apply(I)Lfoo/Box;"))
  }

  test("package-object-forwarders") {
    val source =
      List(
        "package foo",
        "package object bar {",
        "  val x: Int = 1",
        "  def f(): String = \"ok\"",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    assert(classes.containsKey("foo/bar/package$"))
    assert(classes.containsKey("foo/bar/package"))

    val pkg = readMembers(classes.get("foo/bar/package"))
    assert(pkg.methods.contains("x()I"))
    assert(pkg.methods.contains("f()Ljava/lang/String;"))
  }

  test("package-object-forwarders-follow-multiline-builder-return-types") {
    val source =
      List(
        "package foo",
        "package bar {",
        "  class ConfigEntry",
        "  class OptionalConfigEntry",
        "  class ConfigBuilder {",
        "    def doc(v: String): ConfigBuilder = this",
        "    def createWithDefault(v: Int): ConfigEntry = new ConfigEntry",
        "    def createOptional(): OptionalConfigEntry = new OptionalConfigEntry",
        "  }",
        "  object ConfigBuilder {",
        "    def apply(key: String): ConfigBuilder = new ConfigBuilder",
        "  }",
        "}",
        "package object bar {",
        "  private[foo] val A =",
        "    ConfigBuilder(\"a\")",
        "      .doc(\"a\")",
        "      .createWithDefault(1)",
        "  private[foo] val B =",
        "    ConfigBuilder(\"b\")",
        "      .createOptional()",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val module = readMembers(classes.get("foo/bar/package$"))
    assert(module.methods.contains("A()Lfoo/bar/ConfigEntry;"))
    assert(module.methods.contains("B()Lfoo/bar/OptionalConfigEntry;"))
    assert(module.fields.contains("ALfoo/bar/ConfigEntry;"))
    assert(module.fields.contains("BLfoo/bar/OptionalConfigEntry;"))

    val mirror = readMembers(classes.get("foo/bar/package"))
    assert(mirror.methods.contains("A()Lfoo/bar/ConfigEntry;"))
    assert(mirror.methods.contains("B()Lfoo/bar/OptionalConfigEntry;"))
    assert((mirror.methods("A()Lfoo/bar/ConfigEntry;") & Opcodes.ACC_STATIC) != 0)
    assert((mirror.methods("B()Lfoo/bar/OptionalConfigEntry;") & Opcodes.ACC_STATIC) != 0)
  }

  test("default-parameters") {
    val source =
      List(
        "package foo",
        "class C {",
        "  def f(x: Int, y: String = \"x\", z: Long = 1L): Unit = ()",
        "}",
        "object C {",
        "  def g(a: Int, b: String = \"\"): String = b",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/C"))
    assert(cls.methods.contains("f$default$2()Ljava/lang/String;"))
    assert(cls.methods.contains("f$default$3()J"))
    assert((cls.methods("f$default$2()Ljava/lang/String;") & Opcodes.ACC_STATIC) == 0)
    assert(cls.methods.contains("g$default$2()Ljava/lang/String;"))
    assert((cls.methods("g$default$2()Ljava/lang/String;") & Opcodes.ACC_STATIC) != 0)

    val module = readMembers(classes.get("foo/C$"))
    assert(module.methods.contains("g$default$2()Ljava/lang/String;"))
    assert((module.methods("g$default$2()Ljava/lang/String;") & Opcodes.ACC_STATIC) == 0)
  }

  test("ctor-defaults-synthesize-companion") {
    val source =
      List(
        "package foo",
        "class Box(x: Int, y: String = \"\")",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    assert(classes.containsKey("foo/Box$"))
    val module = readMembers(classes.get("foo/Box$"))
    assert(module.fields.contains("MODULE$Lfoo/Box$;"))
    assert(module.methods.contains("$lessinit$greater$default$2()Ljava/lang/String;"))

    val cls = readMembers(classes.get("foo/Box"))
    assert(cls.methods.contains("$lessinit$greater$default$2()Ljava/lang/String;"))
    assert(
      (cls.methods("$lessinit$greater$default$2()Ljava/lang/String;") & Opcodes.ACC_STATIC) != 0
    )
  }

  test("class-private-constructor-modifier-before-params") {
    val source =
      List(
        "package foo",
        "class C private[foo] (val x: Int, y: String) {",
        "  def yLen(): Int = y.length",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/C"))
    assert(cls.methods.contains("<init>(ILjava/lang/String;)V"))
    assert(cls.methods.contains("x()I"))
    assert(cls.methods.contains("yLen()I"))
  }

  test("constructor-param-accessors-are-concrete") {
    val source =
      List(
        "package foo",
        "final class LeaseSettings(val leaseName: String, val ownerName: String)",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/LeaseSettings"))
    assert((cls.methods("leaseName()Ljava/lang/String;") & Opcodes.ACC_ABSTRACT) == 0)
    assert((cls.methods("ownerName()Ljava/lang/String;") & Opcodes.ACC_ABSTRACT) == 0)
  }

  test("case-class-synthetics") {
    val source =
      List(
        "package foo",
        "case class Box(x: Int, y: String)",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/Box"))
    assert(cls.methods.contains("x()I"))
    assert(cls.methods.contains("y()Ljava/lang/String;"))
    assert(cls.methods.contains("copy(ILjava/lang/String;)Lfoo/Box;"))
    assert(cls.methods.contains("productArity()I"))
    assert(cls.methods.contains("productElement(I)Ljava/lang/Object;"))
    assert(cls.methods.contains("productElementName(I)Ljava/lang/String;"))
    assert(cls.methods.contains("productElementNames()Lscala/collection/Iterator;"))
    assert(cls.methods.contains("productPrefix()Ljava/lang/String;"))
    assert(cls.methods.contains("productIterator()Lscala/collection/Iterator;"))
    assert(cls.methods.contains("hashCode()I"))
    assert(cls.methods.contains("equals(Ljava/lang/Object;)Z"))
    assert(cls.methods.contains("canEqual(Ljava/lang/Object;)Z"))
    assert(cls.methods.contains("toString()Ljava/lang/String;"))
    assert(cls.methods.contains("fromProduct(Lscala/Product;)Lfoo/Box;"))
    assert((cls.methods("fromProduct(Lscala/Product;)Lfoo/Box;") & Opcodes.ACC_STATIC) != 0)
    assert(cls.methods.contains("_1()I"))
    assert(cls.methods.contains("_2()Ljava/lang/String;"))
    assert(cls.methods.contains("apply(ILjava/lang/String;)Lfoo/Box;"))
    assert((cls.methods("apply(ILjava/lang/String;)Lfoo/Box;") & Opcodes.ACC_STATIC) != 0)
    assert(cls.methods.contains("unapply(Lfoo/Box;)Lscala/Option;"))

    val module = readMembers(classes.get("foo/Box$"))
    assert(module.methods.contains("apply(ILjava/lang/String;)Lfoo/Box;"))
    assert((module.methods("apply(ILjava/lang/String;)Lfoo/Box;") & Opcodes.ACC_STATIC) == 0)
    assert(module.methods.contains("unapply(Lfoo/Box;)Lscala/Option;"))
    assert(module.methods.contains("fromProduct(Lscala/Product;)Lfoo/Box;"))
    assert((module.methods("fromProduct(Lscala/Product;)Lfoo/Box;") & Opcodes.ACC_STATIC) == 0)
  }

  test("case-class-default-copy") {
    val source =
      List(
        "package foo",
        "case class Box(x: Int = 1, y: String = \"\")",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/Box"))
    assert(cls.methods.contains("copy$default$1()I"))
    assert(cls.methods.contains("copy$default$2()Ljava/lang/String;"))
  }

  test("case-object-synthetics") {
    val source =
      List(
        "package foo",
        "case object Solo {}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    assert(classes.containsKey("foo/Solo$"))
    assert(classes.containsKey("foo/Solo"))

    val module = readMembers(classes.get("foo/Solo$"))
    assert(module.methods.contains("productArity()I"))
    assert(module.methods.contains("productElement(I)Ljava/lang/Object;"))
    assert(module.methods.contains("productElementName(I)Ljava/lang/String;"))
    assert(module.methods.contains("productElementNames()Lscala/collection/Iterator;"))
    assert(module.methods.contains("productIterator()Lscala/collection/Iterator;"))
    assert(module.methods.contains("productPrefix()Ljava/lang/String;"))
    assert(module.methods.contains("hashCode()I"))
    assert(module.methods.contains("toString()Ljava/lang/String;"))
    assert(module.methods.contains("canEqual(Ljava/lang/Object;)Z"))

    val mirror = readMembers(classes.get("foo/Solo"))
    assert(mirror.methods.contains("productArity()I"))
    assert((mirror.methods("productArity()I") & Opcodes.ACC_STATIC) != 0)
  }

  test("case-class-interfaces-skip-inherited-serializable") {
    val source =
      List(
        "package foo",
        "trait Ser extends java.io.Serializable",
        "case class Box(x: Int) extends Ser",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val header = readClassHeader(classes.get("foo/Box"))
    assert(header.interfaces.contains("foo/Ser"))
    assert(header.interfaces.contains("scala/Product"))
    assert(!header.interfaces.contains("java/io/Serializable"))
  }

  test("object-interfaces-skip-inherited-serializable") {
    val source =
      List(
        "package foo",
        "trait Ser extends java.io.Serializable",
        "object O extends Ser",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val header = readClassHeader(classes.get("foo/O$"))
    assertEquals(header.interfaces, List("foo/Ser"))
  }

  test("case-object-interfaces-skip-inherited-product-and-serializable") {
    val source =
      List(
        "package foo",
        "trait ProductLike extends scala.Product with java.io.Serializable",
        "case object O extends ProductLike",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val header = readClassHeader(classes.get("foo/O$"))
    assert(header.interfaces.contains("foo/ProductLike"))
    assert(!header.interfaces.contains("scala/Product"))
    assert(!header.interfaces.contains("java/io/Serializable"))
  }

  test("resolves-imports-for-types") {
    val source =
      List(
        "package foo",
        "import java.util.List",
        "class C(val xs: List)",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/C"))
    assert(cls.methods.contains("xs()Ljava/util/List;"))
  }

  test("object-forwarder-visibility") {
    val source =
      List(
        "package foo",
        "class C",
        "object C {",
        "  private def hidden(): Int = 1",
        "  protected def prot(): Int = 2",
        "  def pub(): Int = 3",
        "  private val secret: String = \"x\"",
        "  protected val shield: Int = 1",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/C"))
    assert(cls.methods.contains("hidden()I"))
    assert(cls.methods.contains("prot()I"))
    assert(cls.methods.contains("pub()I"))
    assert(cls.methods.contains("secret()Ljava/lang/String;"))
    assert(cls.methods.contains("shield()I"))

    assert((cls.methods("hidden()I") & Opcodes.ACC_PRIVATE) != 0)
    assert((cls.methods("hidden()I") & Opcodes.ACC_PUBLIC) == 0)
    assert((cls.methods("prot()I") & Opcodes.ACC_PROTECTED) != 0)
    assert((cls.methods("pub()I") & Opcodes.ACC_PUBLIC) != 0)
    assert((cls.methods("secret()Ljava/lang/String;") & Opcodes.ACC_PRIVATE) != 0)
    assert((cls.methods("shield()I") & Opcodes.ACC_PROTECTED) != 0)
  }

  test("object-forwarders-infer-this-singleton-return") {
    val source =
      List(
        "package foo",
        "object O {",
        "  def getInstance = this",
        "}",
        "class O",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val mirror = readMembers(classes.get("foo/O"))
    assert(mirror.methods.contains("getInstance()Lfoo/O$;"))
    assert((mirror.methods("getInstance()Lfoo/O$;") & Opcodes.ACC_STATIC) != 0)

    val module = readMembers(classes.get("foo/O$"))
    assert(module.methods.contains("getInstance()Lfoo/O$;"))
    assert((module.methods("getInstance()Lfoo/O$;") & Opcodes.ACC_STATIC) == 0)
  }

  test("object-forwarders-prefer-nested-class-over-module-return") {
    val source =
      List(
        "package foo",
        "object O {",
        "  class R",
        "  object R",
        "  def mk(): R = new R",
        "}",
        "class O",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val mirror = readMembers(classes.get("foo/O"))
    assert(mirror.methods.contains("mk()Lfoo/O$R;"))
    assert(!mirror.methods.contains("mk()Lfoo/O$R$;"))
  }

  test("method-type-parameter-erases-to-upper-bound") {
    val source =
      List(
        "package foo",
        "trait Attr",
        "class C {",
        "  def get[T <: Attr](c: Class[T], t: T): T = t",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/C"))
    assert(cls.methods.contains("get(Ljava/lang/Class;Lfoo/Attr;)Lfoo/Attr;"))
  }

  test("throws-annotation-emits-method-exceptions") {
    val source =
      List(
        "package foo",
        "trait F[T, R] {",
        "  @throws(classOf[Exception])",
        "  def apply(t: T): R",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/F"))
    assertEquals(
      cls.methodExceptions("apply(Ljava/lang/Object;)Ljava/lang/Object;"),
      List("java/lang/Exception"),
    )
  }

  test("scoped-private-and-synchronized-defs-lower-to-public-synchronized") {
    val source =
      List(
        "package foo.memory",
        "class C {",
        "  private[memory] def used(task: Long): Long = synchronized { task }",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/memory/C"))
    val access = cls.methods("used(J)J")
    assert((access & Opcodes.ACC_PUBLIC) != 0)
    assert((access & Opcodes.ACC_PRIVATE) == 0)
    assert((access & Opcodes.ACC_SYNCHRONIZED) != 0)
  }

  test("object-forwarders-emit-uppercase-val-accessors") {
    val source =
      List(
        "package foo",
        "object Dispatchers {",
        "  final val DefaultDispatcherId = \"foo.default-dispatcher\"",
        "  final val DefaultBlockingDispatcherId: String = \"foo.default-blocking-dispatcher\"",
        "}",
        "class Dispatchers",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val mirror = readMembers(classes.get("foo/Dispatchers"))
    assert(mirror.methods.contains("DefaultDispatcherId()Ljava/lang/String;"))
    assert(mirror.methods.contains("DefaultBlockingDispatcherId()Ljava/lang/String;"))

    val module = readMembers(classes.get("foo/Dispatchers$"))
    assert(module.methods.contains("DefaultDispatcherId()Ljava/lang/String;"))
    assert(module.methods.contains("DefaultBlockingDispatcherId()Ljava/lang/String;"))
  }

  test("object-mirror-class") {
    val source =
      List(
        "package foo",
        "object Solo {",
        "  val x: Int = 1",
        "  var y: String = \"hi\"",
        "  def bar(z: Int = 2): Int = z",
        "  private def hidden(): Int = 3",
        "  protected def prot(): Int = 4",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    assert(classes.containsKey("foo/Solo$"))
    assert(classes.containsKey("foo/Solo"))

    val mirror = readMembers(classes.get("foo/Solo"))
    assert(mirror.methods.contains("x()I"))
    assert(mirror.methods.contains("y()Ljava/lang/String;"))
    assert(mirror.methods.contains("y_$eq(Ljava/lang/String;)V"))
    assert(mirror.methods.contains("bar(I)I"))
    assert(mirror.methods.contains("bar$default$1()I"))
    assert(!mirror.methods.contains("hidden()I"))
    assert(!mirror.methods.contains("prot()I"))
    assert(!mirror.methods.contains("<init>()V"))
    assert(!mirror.fields.contains("MODULE$Lfoo/Solo$;"))
  }

  test("app-forwarders") {
    val source =
      List(
        "package foo",
        "object Main extends App {",
        "  val x = 1",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val module = readMembers(classes.get("foo/Main$"))
    assert(module.methods.contains("main([Ljava/lang/String;)V"))
    assert(module.methods.contains("delayedInit(Lscala/Function0;)V"))
    assert(module.methods.contains("executionStart()J"))
    assert((module.methods("main([Ljava/lang/String;)V") & Opcodes.ACC_STATIC) == 0)

    val mirror = readMembers(classes.get("foo/Main"))
    assert(mirror.methods.contains("main([Ljava/lang/String;)V"))
    assert(mirror.methods.contains("delayedInit(Lscala/Function0;)V"))
    assert(mirror.methods.contains("executionStart()J"))
    assert((mirror.methods("main([Ljava/lang/String;)V") & Opcodes.ACC_STATIC) != 0)
  }

  test("trait-default-methods") {
    val source =
      List(
        "package foo",
        "trait T {",
        "  def abstractDef(x: Int): Int",
        "  def concreteDef(x: Int): Int = x",
        "  val abstractVal: String",
        "  val concreteVal: String = \"ok\"",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/T"))
    assert((cls.methods("abstractDef(I)I") & Opcodes.ACC_ABSTRACT) != 0)
    assert((cls.methods("concreteDef(I)I") & Opcodes.ACC_ABSTRACT) == 0)
    assert((cls.methods("abstractVal()Ljava/lang/String;") & Opcodes.ACC_ABSTRACT) != 0)
    assert((cls.methods("concreteVal()Ljava/lang/String;") & Opcodes.ACC_ABSTRACT) == 0)
  }

  test("trait-forwarders") {
    val source =
      List(
        "package foo",
        "trait T {",
        "  def foo(): Int = 1",
        "  val bar: String = \"x\"",
        "}",
        "class C extends T {}",
        "object O extends T {}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/C"))
    assert(cls.methods.contains("foo()I"))
    assert(cls.methods.contains("bar()Ljava/lang/String;"))

    val module = readMembers(classes.get("foo/O$"))
    assert(module.methods.contains("foo()I"))
    assert(module.methods.contains("bar()Ljava/lang/String;"))

    val mirror = readMembers(classes.get("foo/O"))
    assert(mirror.methods.contains("foo()I"))
    assert((mirror.methods("foo()I") & Opcodes.ACC_STATIC) != 0)
    assert(mirror.methods.contains("bar()Ljava/lang/String;"))
    assert((mirror.methods("bar()Ljava/lang/String;") & Opcodes.ACC_STATIC) != 0)
  }

  test("trait-forwarders-supertrait") {
    val source =
      List(
        "package foo",
        "trait Base {",
        "  def foo(): Int = 1",
        "}",
        "trait Mid extends Base",
        "class C extends Mid {}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/C"))
    assert(cls.methods.contains("foo()I"))
  }

  test("wildcard-import-object-types") {
    val source =
      List(
        "package foo",
        "object O {",
        "  case class Inner(x: Int)",
        "}",
        "class ActorRef",
        "class C {",
        "  import O._",
        "  def f(a: ActorRef): Unit = ()",
        "  def g(i: Inner): Unit = ()",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/C"))
    assert(cls.methods.contains("f(Lfoo/ActorRef;)V"))
    assert(cls.methods.contains("g(Lfoo/O$Inner;)V"))
  }

  test("wildcard-import-object-types-prefer-class-over-companion-module") {
    val source =
      List(
        "package foo",
        "object O {",
        "  class Inner",
        "  object Inner",
        "}",
        "class C {",
        "  import O._",
        "  def f(i: Inner): Inner = i",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/C"))
    assert(cls.methods.contains("f(Lfoo/O$Inner;)Lfoo/O$Inner;"))
  }

  test("wildcard-import-qualified-object-types-prefer-class-over-companion-module") {
    val source1 =
      List(
        "package foo.core",
        "object Effect {",
        "  class Spawned[T]",
        "  object Spawned",
        "}",
      ).mkString("\n")
    val source2 =
      List(
        "package foo.api",
        "class Effects {",
        "  import foo.core.Effect._",
        "  def spawned[T](s: Spawned[T]): Spawned[T] = s",
        "}",
      ).mkString("\n")

    val unit1 = ScalaParser.parse(new SourceFile(null, source1))
    val unit2 = ScalaParser.parse(new SourceFile(null, source2))
    val classes =
      ScalaLower.lower(
        ImmutableList.of(unit1, unit2),
        LanguageVersion.createDefault().majorVersion(),
      )

    val cls = readMembers(classes.get("foo/api/Effects"))
    assert(
      cls.methods.contains("spawned(Lfoo/core/Effect$Spawned;)Lfoo/core/Effect$Spawned;")
    )
  }

  test("local-imports-do-not-override-unit-explicit-imports") {
    val source1 =
      List(
        "package foo.actor",
        "class ActorRef",
      ).mkString("\n")
    val source2 =
      List(
        "package foo.actor.typed",
        "class ActorRef",
      ).mkString("\n")
    val source3 =
      List(
        "package foo.api",
        "import foo.actor.typed.ActorRef",
        "object O {",
        "  class Inner",
        "}",
        "class C {",
        "  import O._",
        "  def f(a: ActorRef): ActorRef = a",
        "  def g(i: Inner): Inner = i",
        "}",
      ).mkString("\n")

    val unit1 = ScalaParser.parse(new SourceFile(null, source1))
    val unit2 = ScalaParser.parse(new SourceFile(null, source2))
    val unit3 = ScalaParser.parse(new SourceFile(null, source3))
    val classes =
      ScalaLower.lower(
        ImmutableList.of(unit1, unit2, unit3),
        LanguageVersion.createDefault().majorVersion(),
      )

    val cls = readMembers(classes.get("foo/api/C"))
    assert(cls.methods.contains("f(Lfoo/actor/typed/ActorRef;)Lfoo/actor/typed/ActorRef;"))
    assert(cls.methods.contains("g(Lfoo/api/O$Inner;)Lfoo/api/O$Inner;"))
  }

  test("unit-explicit-imports-beat-current-package-classpath-fallback") {
    val source1 =
      List(
        "package foo.actor.typed",
        "class ActorRef",
      ).mkString("\n")
    val source2 =
      List(
        "package foo.api",
        "import foo.actor.typed.ActorRef",
        "class C {",
        "  def f(a: ActorRef): ActorRef = a",
        "}",
      ).mkString("\n")

    val resolver = new ScalaLower.ParentKindResolver {
      override def isInterface(binaryName: String): Boolean = false

      override def superName(binaryName: String): String =
        binaryName match {
          case "foo/api/ActorRef" => "java/lang/Object"
          case _ => null
        }
    }

    val unit1 = ScalaParser.parse(new SourceFile(null, source1))
    val unit2 = ScalaParser.parse(new SourceFile(null, source2))
    val classes =
      ScalaLower.lower(
        ImmutableList.of(unit1, unit2),
        LanguageVersion.createDefault().majorVersion(),
        resolver,
      )

    val cls = readMembers(classes.get("foo/api/C"))
    assert(cls.methods.contains("f(Lfoo/actor/typed/ActorRef;)Lfoo/actor/typed/ActorRef;"))
    assert(!cls.methods.contains("f(Lfoo/api/ActorRef;)Lfoo/api/ActorRef;"))
  }

  test("wildcard-import-object-types-over-package") {
    val source =
      List(
        "package foo",
        "import scala.concurrent.duration._",
        "object O {",
        "  case class Inner(x: Int)",
        "}",
        "class C {",
        "  import O._",
        "  def g(i: Inner): Unit = ()",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/C"))
    assert(cls.methods.contains("g(Lfoo/O$Inner;)V"))
  }

  test("trait-self-type-members") {
    val source =
      List(
        "package foo",
        "class Base",
        "trait T { this: Base =>",
        "  def self(): Int = 1",
        "}",
        "class C extends Base with T {}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/C"))
    assert(cls.methods.contains("self()I"))
  }

  test("object-import-object-types") {
    val source =
      List(
        "package foo",
        "import scala.concurrent.Future",
        "import scala.concurrent.duration._",
        "object CommonMapAsync {",
        "  case class EntityEvent(id: Int)",
        "}",
        "object MapAsyncPartitioned extends App {",
        "  import CommonMapAsync._",
        "  def processEvent(event: EntityEvent, partition: Int): Future[String] =",
        "    Future.successful(\"\")",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val mirror = readMembers(classes.get("foo/MapAsyncPartitioned"))
    assert(
      mirror.methods.contains("processEvent(Lfoo/CommonMapAsync$EntityEvent;I)Lscala/concurrent/Future;")
    )
  }

  test("infers-forwarder-return-type-from-later-helper") {
    val source =
      List(
        "package foo",
        "final class Settings(private val value: Int) {",
        "  def withValue(v: Int) = copy(v)",
        "  private def copy(v: Int) = new Settings(v)",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/Settings"))
    assert(cls.methods.contains("withValue(I)Lfoo/Settings;"))
  }

  test("trait-inferred-val-concrete") {
    val source =
      List(
        "package foo",
        "trait T {",
        "  val concrete = \"ok\"",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/T"))
    assert((cls.methods("concrete()Ljava/lang/String;") & Opcodes.ACC_ABSTRACT) == 0)
  }

  test("abstract-class-members") {
    val source =
      List(
        "package foo",
        "abstract class C {",
        "  def abstractDef(x: Int): Int",
        "  val abstractVal: String",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/C"))
    assert((cls.methods("abstractDef(I)I") & Opcodes.ACC_ABSTRACT) != 0)
    assert((cls.methods("abstractVal()Ljava/lang/String;") & Opcodes.ACC_ABSTRACT) != 0)
  }

  test("resolves-selector-import-renames") {
    val source =
      List(
        "package foo",
        "import java.util.{List => JList, _}",
        "class C(val xs: JList, val ys: ArrayList)",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val resolver = new ScalaLower.ParentKindResolver {
      override def isInterface(binaryName: String): Boolean = false

      override def superName(binaryName: String): String =
        binaryName match {
          case "java/util/ArrayList" => "java/util/AbstractList"
          case _ => null
        }
    }
    val classes =
      ScalaLower.lower(
        ImmutableList.of(unit),
        LanguageVersion.createDefault().majorVersion(),
        resolver,
      )

    val cls = readMembers(classes.get("foo/C"))
    assert(cls.methods.contains("xs()Ljava/util/List;"))
    assert(cls.methods.contains("ys()Ljava/util/ArrayList;"))
  }

  test("resolves-object-wildcard-imports") {
    val source =
      List(
        "package foo",
        "object Outer {",
        "  case class Inner(x: Int)",
        "}",
        "object Use {",
        "  import Outer._",
        "  def f(i: Inner): Int = i.x",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val module = readMembers(classes.get("foo/Use$"))
    assert(module.methods.contains("f(Lfoo/Outer$Inner;)I"))
  }

  test("resolves-type-aliases") {
    val source =
      List(
        "package foo",
        "import java.util.List",
        "class C {",
        "  type JList = List",
        "  def f(xs: JList): JList = xs",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/C"))
    assert(cls.methods.contains("f(Ljava/util/List;)Ljava/util/List;"))
  }

  test("infers-new-expression-types") {
    val source =
      List(
        "package foo",
        "import java.lang.StringBuilder",
        "import java.util.Random",
        "class C {",
        "  val rng = new Random()",
        "  val bytes = new Array[Byte](1)",
        "  def builder = new StringBuilder()",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/C"))
    assert(cls.methods.contains("rng()Ljava/util/Random;"))
    assert(cls.methods.contains("bytes()[B"))
    assert(cls.methods.contains("builder()Ljava/lang/StringBuilder;"))
  }

  test("function-type-descriptors") {
    val source =
      List(
        "package foo",
        "class C {",
        "  val f: Int => String = _ => \"\"",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/C"))
    assert(cls.methods.contains("f()Lscala/Function1;"))
  }

  test("varargs-bridges-and-static-forwarders") {
    val source =
      List(
        "package foo",
        "class C {",
        "  def many(xs: Int*): Int = 0",
        "}",
        "object C {",
        "  def fromInts(xs: Int*): Int = 0",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/C"))
    assert(cls.methods.contains("many([I)I"))
    assert((cls.methods("many([I)I") & Opcodes.ACC_VARARGS) != 0)
    assert((cls.methods("many([I)I") & Opcodes.ACC_STATIC) == 0)

    assert(cls.methods.contains("fromInts([I)I"))
    assert((cls.methods("fromInts([I)I") & Opcodes.ACC_STATIC) != 0)
    assert((cls.methods("fromInts([I)I") & Opcodes.ACC_VARARGS) != 0)
    assert((cls.methods("fromInts([I)I") & Opcodes.ACC_FINAL) == 0)
  }

  test("resolves-core-java-and-scala-type-names") {
    val source =
      List(
        "package foo",
        "import java.io.File",
        "trait T {",
        "  def f(r: Runnable, pf: PartialFunction, it: Iterable, s: Serializable, file: File): Unit",
        "  def g(xs: immutable.Seq): immutable.Seq",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/T"))
    assert(
      cls.methods.contains(
        "f(Ljava/lang/Runnable;Lscala/PartialFunction;Lscala/collection/Iterable;Ljava/io/Serializable;Ljava/io/File;)V"
      )
    )
    assert(
      cls.methods.contains(
        "g(Lscala/collection/immutable/Seq;)Lscala/collection/immutable/Seq;"
      )
    )
  }

  test("resolves-imported-package-prefix-types") {
    val source =
      List(
        "package foo",
        "import demo.lib.fn",
        "class C {",
        "  def run(cb: fn.Procedure): Unit = ()",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/C"))
    assert(cls.methods.contains("run(Ldemo/lib/fn/Procedure;)V"))
  }

  test("qualified-package-heads-and-scala-tuples") {
    val source =
      List(
        "package demo.stream.api",
        "import demo.lib.fn._",
        "import demo.stream.core._",
        "import example.pkg._",
        "class C {",
        "  def run(cb: fn.Function): Unit = ()",
        "  def lift(src: core.Source): core.Source = src",
        "  def pair(p: Tuple2): scala.Tuple2 = p",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("demo/stream/api/C"))
    assert(cls.methods.contains("run(Ldemo/lib/fn/Function;)V"))
    assert(
      cls.methods.contains(
        "lift(Ldemo/stream/core/Source;)Ldemo/stream/core/Source;"
      )
    )
    assert(cls.methods.contains("pair(Lscala/Tuple2;)Lscala/Tuple2;"))
  }

  test("resolves-sibling-package-prefix-types") {
    val source =
      List(
        "package com.example.ml.util {",
        "  class DefaultParamsWritable",
        "}",
        "package com.example.ml.feature {",
        "  class C {",
        "    def writable(p: util.DefaultParamsWritable): util.DefaultParamsWritable = p",
        "  }",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("com/example/ml/feature/C"))
    assert(
      cls.methods.contains(
        "writable(Lcom/example/ml/util/DefaultParamsWritable;)Lcom/example/ml/util/DefaultParamsWritable;"
      )
    )
  }

  test("relative-package-prefix-prefers-nearest-child-package") {
    val source =
      List(
        "package demo.api.javadsl {",
        "  class EntityTypeKey",
        "}",
        "package demo.javadsl {",
        "  class EntityTypeKey",
        "}",
        "package demo.api {",
        "  class Query {",
        "    def keyOf(k: javadsl.EntityTypeKey): javadsl.EntityTypeKey = k",
        "  }",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("demo/api/Query"))
    assert(
      cls.methods.contains(
        "keyOf(Ldemo/api/javadsl/EntityTypeKey;)Ldemo/api/javadsl/EntityTypeKey;"
      )
    )
  }

  test("generic-varargs-bridges") {
    val source =
      List(
        "package foo",
        "class JavaRDD[T]",
        "class JavaPairRDD[K, V]",
        "class JavaDoubleRDD",
        "class C {",
        "  @varargs",
        "  def union[T](rdds: JavaRDD[T]*): JavaRDD[T] = null",
        "  @varargs",
        "  def union[K, V](rdds: JavaPairRDD[K, V]*): JavaPairRDD[K, V] = null",
        "  @varargs",
        "  def union(rdds: JavaDoubleRDD*): JavaDoubleRDD = null",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/C"))
    assert(cls.methods.contains("union(Lscala/collection/immutable/Seq;)Lfoo/JavaRDD;"))
    assert(cls.methods.contains("union([Lfoo/JavaRDD;)Lfoo/JavaRDD;"))
    assert((cls.methods("union([Lfoo/JavaRDD;)Lfoo/JavaRDD;") & Opcodes.ACC_VARARGS) != 0)
    assert(cls.methods.contains("union(Lscala/collection/immutable/Seq;)Lfoo/JavaPairRDD;"))
    assert(cls.methods.contains("union([Lfoo/JavaPairRDD;)Lfoo/JavaPairRDD;"))
    assert((cls.methods("union([Lfoo/JavaPairRDD;)Lfoo/JavaPairRDD;") & Opcodes.ACC_VARARGS) != 0)
    assert(cls.methods.contains("union(Lscala/collection/immutable/Seq;)Lfoo/JavaDoubleRDD;"))
    assert(cls.methods.contains("union([Lfoo/JavaDoubleRDD;)Lfoo/JavaDoubleRDD;"))
    assert((cls.methods("union([Lfoo/JavaDoubleRDD;)Lfoo/JavaDoubleRDD;") & Opcodes.ACC_VARARGS) != 0)
  }

  test("root-package-heads-and-term-wildcards") {
    val actorSource =
      List(
        "package demo.actor",
        "class ActorRef {}",
        "class ActorSystem {}",
      ).mkString("\n")

    val aliasSource =
      List(
        "package demo.event",
        "object Levels {",
        "  type Level = Int",
        "}",
      ).mkString("\n")

    val clusterSource =
      List(
        "package demo.cluster",
        "import demo.actor._",
        "import demo.event.Levels.Level",
        "class Settings",
        "class C {",
        "  val settings = new Settings",
        "  import settings._",
        "  def ref(x: ActorRef): ActorRef = x",
        "  def systemOf(x: ActorSystem): ActorSystem = x",
        "  def levelOf(x: Level): Level = x",
        "}",
      ).mkString("\n")

    val javaSource =
      List(
        "package example.api",
        "import java.{lang, util}",
        "class J {",
        "  def bool(x: java.lang.Boolean): java.lang.Boolean = x",
        "  def builder(x: lang.StringBuilder): lang.StringBuilder = x",
        "  def listOf(xs: util.List[String]): util.List[String] = xs",
        "}",
      ).mkString("\n")

    val actorUnit = ScalaParser.parse(new SourceFile(null, actorSource))
    val aliasUnit = ScalaParser.parse(new SourceFile(null, aliasSource))
    val clusterUnit = ScalaParser.parse(new SourceFile(null, clusterSource))
    val javaUnit = ScalaParser.parse(new SourceFile(null, javaSource))
    val classes =
      ScalaLower.lower(
        ImmutableList.of(actorUnit, aliasUnit, clusterUnit, javaUnit),
        LanguageVersion.createDefault().majorVersion(),
      )

    val clusterCls = readMembers(classes.get("demo/cluster/C"))
    assert(
      clusterCls.methods.contains("ref(Ldemo/actor/ActorRef;)Ldemo/actor/ActorRef;")
    )
    assert(
      clusterCls.methods.contains("systemOf(Ldemo/actor/ActorSystem;)Ldemo/actor/ActorSystem;")
    )
    assert(clusterCls.methods.contains("levelOf(I)I"))

    val javaCls = readMembers(classes.get("example/api/J"))
    assert(javaCls.methods.contains("bool(Ljava/lang/Boolean;)Ljava/lang/Boolean;"))
    assert(
      javaCls.methods.contains("builder(Ljava/lang/StringBuilder;)Ljava/lang/StringBuilder;")
    )
    assert(javaCls.methods.contains("listOf(Ljava/util/List;)Ljava/util/List;"))
  }

  test("classpath-wildcard-imports-do-not-capture-unresolved-simple-types") {
    val source =
      List(
        "package demo.api",
        "import akka.actor._",
        "import java.util.concurrent._",
        "class C {",
        "  def systemOf(x: ActorSystem): ActorSystem = x",
        "  def tableOf(x: ConcurrentHashMap): ConcurrentHashMap = x",
        "}",
      ).mkString("\n")

    val resolver = new ScalaLower.ParentKindResolver {
      override def isInterface(binaryName: String): Boolean = false

      override def superName(binaryName: String): String =
        binaryName match {
          case "akka/actor/ActorSystem" => "java/lang/Object"
          case "java/util/concurrent/ConcurrentHashMap" => "java/util/AbstractMap"
          case _ => null
        }
    }

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(
        ImmutableList.of(unit),
        LanguageVersion.createDefault().majorVersion(),
        resolver,
      )

    val cls = readMembers(classes.get("demo/api/C"))
    assert(
      cls.methods.contains("systemOf(Lakka/actor/ActorSystem;)Lakka/actor/ActorSystem;")
    )
    assert(
      cls.methods.contains(
        "tableOf(Ljava/util/concurrent/ConcurrentHashMap;)Ljava/util/concurrent/ConcurrentHashMap;"
      )
    )
  }

  test("classpath-wildcard-imports-resolve-per-package-members") {
    val source =
      List(
        "package demo.sql",
        "import org.apache.spark.api.java.function._",
        "import org.apache.spark.sql.streaming._",
        "class C {",
        "  def state(",
        "      f: FlatMapGroupsWithStateFunction,",
        "      o: OutputMode,",
        "      t: GroupStateTimeout",
        "  ): Unit = ()",
        "}",
      ).mkString("\n")

    val resolver = new ScalaLower.ParentKindResolver {
      override def isInterface(binaryName: String): Boolean =
        binaryName == "org/apache/spark/api/java/function/FlatMapGroupsWithStateFunction"

      override def superName(binaryName: String): String =
        binaryName match {
          case "org/apache/spark/sql/streaming/OutputMode" => "java/lang/Object"
          case "org/apache/spark/sql/streaming/GroupStateTimeout" => "java/lang/Object"
          case _ => null
        }
    }

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(
        ImmutableList.of(unit),
        LanguageVersion.createDefault().majorVersion(),
        resolver,
      )

    val cls = readMembers(classes.get("demo/sql/C"))
    assert(
      cls.methods.contains(
        "state(Lorg/apache/spark/api/java/function/FlatMapGroupsWithStateFunction;Lorg/apache/spark/sql/streaming/OutputMode;Lorg/apache/spark/sql/streaming/GroupStateTimeout;)V"
      )
    )
  }

  test("qualified-type-aliases-and-imported-typed-system") {
    val actorSource =
      List(
        "package demo.actor",
        "object Supervisor {",
        "  type Decider = PartialFunction[Throwable, Directive]",
        "  type JDecider = demo.fn.Function",
        "  type Level = Int",
        "  trait Directive",
        "}",
        "class C(",
        "  decider: Supervisor.Decider,",
        "  jdecider: Supervisor.JDecider,",
        "  level: Supervisor.Level",
        ") {",
        "  def decide(d: Supervisor.Decider): Supervisor.Decider = d",
        "  def decideJ(d: Supervisor.JDecider): Supervisor.JDecider = d",
        "  def current(level: Supervisor.Level): Supervisor.Level = level",
        "  def product5Of(p: Product5): Product5 = p",
        "}",
      ).mkString("\n")

    val functionSource =
      List(
        "package demo.fn",
        "class Function {}",
      ).mkString("\n")

    val typedModelSource =
      List(
        "package demo.actor.typed",
        "class System[T] {}",
      ).mkString("\n")

    val typedSource =
      List(
        "package demo.cluster.typed",
        "import demo.actor.typed.System",
        "class D {",
        "  def create(system: System[_]): System[_] = system",
        "}",
      ).mkString("\n")

    val actorUnit = ScalaParser.parse(new SourceFile(null, actorSource))
    val functionUnit = ScalaParser.parse(new SourceFile(null, functionSource))
    val typedModelUnit = ScalaParser.parse(new SourceFile(null, typedModelSource))
    val typedUnit = ScalaParser.parse(new SourceFile(null, typedSource))
    val classes =
      ScalaLower.lower(
        ImmutableList.of(actorUnit, functionUnit, typedModelUnit, typedUnit),
        LanguageVersion.createDefault().majorVersion(),
      )

    val actorCls = readMembers(classes.get("demo/actor/C"))
    assert(
      actorCls.methods.contains("<init>(Lscala/PartialFunction;Ldemo/fn/Function;I)V"),
      actorCls.methods.keys.toList.sorted.mkString("\n"),
    )
    assert(
      actorCls.methods.contains("decide(Lscala/PartialFunction;)Lscala/PartialFunction;")
    )
    assert(actorCls.methods.contains("decideJ(Ldemo/fn/Function;)Ldemo/fn/Function;"))
    assert(actorCls.methods.contains("current(I)I"))
    assert(actorCls.methods.contains("product5Of(Lscala/Product5;)Lscala/Product5;"))

    val typedCls = readMembers(classes.get("demo/cluster/typed/D"))
    assert(
      typedCls.methods.contains(
        "create(Ldemo/actor/typed/System;)Ldemo/actor/typed/System;"
      )
    )
  }

  test("nested-case-object-module-shape-and-package-qualified-types") {
    val source =
      List(
        "package demo.stream.api",
        "object Holder {",
        "  sealed trait Directive {",
        "    private[demo] def logLevel: Int",
        "  }",
        "  private[demo] sealed class Resume(private[demo] val logLevel: Int) extends Directive",
        "  case object Resume extends Resume(1)",
        "}",
        "class Source",
        "class C {",
        "  def from(src: api.Source): api.Source = src",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val moduleHeader = readClassHeader(classes.get("demo/stream/api/Holder$Resume$"))
    assertEquals(moduleHeader.superName, "demo/stream/api/Holder$Resume")
    assert((moduleHeader.access & Opcodes.ACC_FINAL) == 0)
    assert(moduleHeader.interfaces.contains("scala/Product"))
    assert(moduleHeader.interfaces.contains("java/io/Serializable"))
    assert(!moduleHeader.interfaces.contains("scala/deriving/Mirror$Singleton"))

    val cls = readMembers(classes.get("demo/stream/api/C"))
    assert(
      cls.methods.contains("from(Ldemo/stream/api/Source;)Ldemo/stream/api/Source;")
    )
  }

  test("local-nested-types-shadow-package-wildcards") {
    val source =
      List(
        "package foo",
        "object Common {",
        "  import scala.concurrent.duration._",
        "  sealed trait Event",
        "  object Consumer",
        "  def consumer = Consumer",
        "  def handle(e: Event): Event = e",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val module = readMembers(classes.get("foo/Common$"))
    assert(module.methods.contains("consumer()Lfoo/Common$Consumer$;"))
    assert(module.methods.contains("handle(Lfoo/Common$Event;)Lfoo/Common$Event;"))
  }

  test("emits-recursive-nested-source-defs") {
    val source =
      List(
        "package foo",
        "object Outer {",
        "  class A",
        "  class B {",
        "    def f(a: A): A = a",
        "  }",
        "  object Mid {",
        "    class C",
        "  }",
        "  class D {",
        "    import Mid._",
        "    def g(c: C): C = c",
        "  }",
        "  class Holder {",
        "    class Nested",
        "    object Worker",
        "  }",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    assert(classes.containsKey("foo/Outer$A"))
    assert(classes.containsKey("foo/Outer$B"))
    assert(classes.containsKey("foo/Outer$Mid$"))
    assert(classes.containsKey("foo/Outer$Mid$C"))
    assert(classes.containsKey("foo/Outer$D"))
    assert(classes.containsKey("foo/Outer$Holder"))
    assert(classes.containsKey("foo/Outer$Holder$Nested"))
    assert(classes.containsKey("foo/Outer$Holder$Worker$"))

    assert(!classes.containsKey("foo/Outer$Mid"))
    assert(!classes.containsKey("foo/Outer$Holder$Worker"))

    val b = readMembers(classes.get("foo/Outer$B"))
    assert(b.methods.contains("f(Lfoo/Outer$A;)Lfoo/Outer$A;"))

    val d = readMembers(classes.get("foo/Outer$D"))
    assert(d.methods.contains("g(Lfoo/Outer$Mid$C;)Lfoo/Outer$Mid$C;"))
  }

  test("normalizes-this-type-method-returns") {
    val source =
      List(
        "package foo",
        "class C {",
        "  def set(v: Int): this.type = this",
        "}",
        "object O {",
        "  def set(v: Int): this.type = this",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/C"))
    assert(cls.methods.contains("set(I)Lfoo/C;"))

    val module = readMembers(classes.get("foo/O$"))
    assert(module.methods.contains("set(I)Lfoo/O$;"))

    val mirror = readMembers(classes.get("foo/O"))
    assert(mirror.methods.contains("set(I)Lfoo/O$;"))
  }

  test("infers-object-expression-return-types") {
    val source =
      List(
        "package foo",
        "object FromConfig",
        "object Plugins {",
        "  object PersistenceTestKitPlugin",
        "}",
        "class C {",
        "  def getInstance = FromConfig",
        "  import Plugins._",
        "  def plugin = PersistenceTestKitPlugin",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/C"))
    assert(cls.methods.contains("getInstance()Lfoo/FromConfig$;"))
    assert(cls.methods.contains("plugin()Lfoo/Plugins$PersistenceTestKitPlugin$;"))
  }

  test("falls-back-uninferred-member-types-to-object") {
    val source =
      List(
        "package foo",
        "class C {",
        "  def inferred = unknown.value",
        "  val data = unknown.value",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/C"))
    assert(cls.methods.contains("inferred()Ljava/lang/Object;"))
    assert(cls.methods.contains("data()Ljava/lang/Object;"))
  }

  test("class-parent-interface-from-resolver") {
    val source =
      List(
        "package foo",
        "import java.sql.Driver",
        "class C extends Driver {}",
      ).mkString("\n")

    val resolver: ScalaLower.ParentKindResolver =
      (binaryName: String) => binaryName == "java/sql/Driver"
    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(
        ImmutableList.of(unit),
        LanguageVersion.createDefault().majorVersion(),
        resolver,
      )

    val header = readClassHeader(classes.get("foo/C"))
    assertEquals(header.superName, "java/lang/Object")
    assertEquals(header.interfaces, List("java/sql/Driver"))
  }

  test("same-package-classpath-type-beats-wildcard-import") {
    val source =
      List(
        "package foo.config {",
        "  class MemoryMode",
        "}",
        "package foo {",
        "  import foo.config._",
        "  class C {",
        "    def use(mode: MemoryMode): MemoryMode = mode",
        "  }",
        "}",
      ).mkString("\n")

    val resolver = new ScalaLower.ParentKindResolver {
      override def isInterface(binaryName: String): Boolean = false

      override def superName(binaryName: String): String =
        binaryName match {
          case "foo/MemoryMode" => "java/lang/Object"
          case _ => null
        }
    }

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(
        ImmutableList.of(unit),
        LanguageVersion.createDefault().majorVersion(),
        resolver,
      )

    val cls = readMembers(classes.get("foo/C"))
    assert(cls.methods.contains("use(Lfoo/MemoryMode;)Lfoo/MemoryMode;"))
  }

  test("package-object-type-alias-beats-wildcard-import-type") {
    val sqlSource =
      List(
        "package demo.sql",
        "class Dataset",
      ).mkString("\n")

    val packageObjectSource =
      List(
        "package demo",
        "package object sql {",
        "  type DataFrame = sql.Dataset",
        "}",
      ).mkString("\n")

    val functionsSource =
      List(
        "package demo.sql.functions",
        "class DataFrame",
      ).mkString("\n")

    val modelSource =
      List(
        "package demo.sql",
        "import demo.sql.functions._",
        "class Model {",
        "  def transform(ds: Dataset): DataFrame = ds",
        "}",
      ).mkString("\n")

    val sqlUnit = ScalaParser.parse(new SourceFile(null, sqlSource))
    val packageObjectUnit = ScalaParser.parse(new SourceFile(null, packageObjectSource))
    val functionsUnit = ScalaParser.parse(new SourceFile(null, functionsSource))
    val modelUnit = ScalaParser.parse(new SourceFile(null, modelSource))
    val classes =
      ScalaLower.lower(
        ImmutableList.of(sqlUnit, packageObjectUnit, functionsUnit, modelUnit),
        LanguageVersion.createDefault().majorVersion(),
      )

    val model = readMembers(classes.get("demo/sql/Model"))
    assert(model.methods.contains("transform(Ldemo/sql/Dataset;)Ldemo/sql/Dataset;"))
    assert(!model.methods.contains("transform(Ldemo/sql/Dataset;)Ldemo/sql/functions/DataFrame;"))
  }

  test("object-interfaces-skip-inherited-serializable-from-resolver") {
    val source =
      List(
        "package foo",
        "import java.sql.Driver",
        "object O extends Driver {}",
      ).mkString("\n")

    val resolver = new ScalaLower.ParentKindResolver {
      override def isInterface(binaryName: String): Boolean =
        binaryName == "java/sql/Driver"

      override def interfaces(binaryName: String): ImmutableList[String] =
        binaryName match {
          case "java/sql/Driver" => ImmutableList.of("java/io/Serializable")
          case _ => ImmutableList.of()
        }
    }

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(
        ImmutableList.of(unit),
        LanguageVersion.createDefault().majorVersion(),
        resolver,
      )

    val header = readClassHeader(classes.get("foo/O$"))
    assertEquals(header.superName, "java/lang/Object")
    assertEquals(header.interfaces, List("java/sql/Driver"))
  }

  test("class-parent-interface-prefers-local-package-object") {
    val source =
      List(
        "package foo.io {",
        "  import foo.actor.IO",
        "  object IO {",
        "    trait Extension",
        "  }",
        "  class C extends IO.Extension",
        "}",
        "package foo.actor {",
        "  object IO {",
        "    class Extension",
        "  }",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val header = readClassHeader(classes.get("foo/io/C"))
    assertEquals(header.superName, "java/lang/Object")
    assertEquals(header.interfaces, List("foo/io/IO$Extension"))
  }

  test("nested-parent-prefers-enclosing-member-over-current-package-classpath") {
    val source =
      List(
        "package foo",
        "class Status",
        "object Status {",
        "  trait Status",
        "  final class Success extends Status",
        "}",
      ).mkString("\n")

    val resolver = new ScalaLower.ParentKindResolver {
      override def isInterface(binaryName: String): Boolean =
        binaryName == "foo/Status$Status"

      override def superName(binaryName: String): String =
        binaryName match {
          case "foo/Status" => "java/lang/Object"
          case _ => null
        }
    }

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(
        ImmutableList.of(unit),
        LanguageVersion.createDefault().majorVersion(),
        resolver,
      )

    val header = readClassHeader(classes.get("foo/Status$Success"))
    assertEquals(header.superName, "java/lang/Object")
    assertEquals(header.interfaces, List("foo/Status$Status"))
  }

  test("nested-parent-uses-enclosing-import-chain") {
    val source =
      List(
        "package foo.internal {",
        "  object Impl {",
        "    trait Marker",
        "  }",
        "}",
        "package foo.api {",
        "  object Api {",
        "    import foo.internal.Impl",
        "    import Impl.Marker",
        "    trait Command extends Marker",
        "    final class Ack extends Marker",
        "  }",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val commandHeader = readClassHeader(classes.get("foo/api/Api$Command"))
    assertEquals(commandHeader.superName, "java/lang/Object")
    assertEquals(commandHeader.interfaces, List("foo/internal/Impl$Marker"))

    val ackHeader = readClassHeader(classes.get("foo/api/Api$Ack"))
    assertEquals(ackHeader.superName, "java/lang/Object")
    assertEquals(ackHeader.interfaces, List("foo/internal/Impl$Marker"))
  }

  test("nested-parent-owner-import-chain-can-use-unit-imports") {
    val source =
      List(
        "package foo.internal {",
        "  object Impl {",
        "    trait Marker",
        "  }",
        "}",
        "package foo.api {",
        "  import foo.internal.Impl",
        "  object Api {",
        "    import Impl.Marker",
        "    trait Command extends Marker",
        "    final class Ack extends Marker",
        "  }",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val commandHeader = readClassHeader(classes.get("foo/api/Api$Command"))
    assertEquals(commandHeader.superName, "java/lang/Object")
    assertEquals(commandHeader.interfaces, List("foo/internal/Impl$Marker"))

    val ackHeader = readClassHeader(classes.get("foo/api/Api$Ack"))
    assertEquals(ackHeader.superName, "java/lang/Object")
    assertEquals(ackHeader.interfaces, List("foo/internal/Impl$Marker"))
  }

  test("class-parent-trait-with-class-superclass") {
    val source =
      List(
        "package foo",
        "abstract class Base",
        "trait Parent extends Base",
        "trait Child extends Parent",
        "class C extends Child",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val header = readClassHeader(classes.get("foo/C"))
    assertEquals(header.superName, "foo/Base")
    assertEquals(header.interfaces, List("foo/Child"))
  }

  test("class-parent-prunes-redundant-direct-interfaces") {
    val source =
      List(
        "package foo",
        "trait ReadJournal",
        "trait Query extends ReadJournal",
        "class C extends ReadJournal with Query",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val header = readClassHeader(classes.get("foo/C"))
    assertEquals(header.superName, "java/lang/Object")
    assertEquals(header.interfaces, List("foo/Query"))
  }

  test("class-parent-prunes-single-interface-inherited-from-superclass") {
    val source =
      List(
        "package foo",
        "class Base extends java.io.Serializable",
        "class C extends Base with java.io.Serializable",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val header = readClassHeader(classes.get("foo/C"))
    assertEquals(header.superName, "foo/Base")
    assertEquals(header.interfaces, Nil)
  }

  test("class-parent-normalizes-scala-serializable") {
    val source =
      List(
        "package foo",
        "trait Product extends scala.Equals",
        "class C extends Product with scala.Serializable with scala.Equals",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val header = readClassHeader(classes.get("foo/C"))
    assertEquals(header.superName, "java/lang/Object")
    assertEquals(header.interfaces, List("foo/Product", "java/io/Serializable"))
  }

  test("trait-forwarders-prefer-local-package-object-parent") {
    val source =
      List(
        "package foo.io {",
        "  import foo.actor.IO",
        "  object IO {",
        "    trait Extension[A] {",
        "      def id(value: A): A = value",
        "      def const: String = \"ok\"",
        "    }",
        "  }",
        "  class C extends IO.Extension[String] {}",
        "  object O extends IO.Extension[String] {}",
        "}",
        "package foo.actor {",
        "  object IO {",
        "    class Extension",
        "  }",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/io/C"))
    assert(cls.methods.contains("id(Ljava/lang/String;)Ljava/lang/String;"))
    assert(cls.methods.contains("const()Ljava/lang/String;"))

    val module = readMembers(classes.get("foo/io/O$"))
    assert(module.methods.contains("id(Ljava/lang/String;)Ljava/lang/String;"))
    assert(module.methods.contains("const()Ljava/lang/String;"))

    val mirror = readMembers(classes.get("foo/io/O"))
    assert(mirror.methods.contains("id(Ljava/lang/String;)Ljava/lang/String;"))
    assert((mirror.methods("id(Ljava/lang/String;)Ljava/lang/String;") & Opcodes.ACC_STATIC) != 0)
    assert(mirror.methods.contains("const()Ljava/lang/String;"))
    assert((mirror.methods("const()Ljava/lang/String;") & Opcodes.ACC_STATIC) != 0)
  }

  test("parent-resolution-prefers-known-package-wildcard-members") {
    val source =
      List(
        "package foo.alpha {",
        "  trait Parent {",
        "    def ping(value: String): String = value",
        "  }",
        "}",
        "package foo.beta {",
        "  class Marker",
        "}",
        "package foo.use {",
        "  import foo.alpha._",
        "  import foo.beta._",
        "  class C extends Parent {}",
        "  object O extends Parent {}",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val classHeader = readClassHeader(classes.get("foo/use/C"))
    assertEquals(classHeader.superName, "java/lang/Object")
    assertEquals(classHeader.interfaces, List("foo/alpha/Parent"))

    val cls = readMembers(classes.get("foo/use/C"))
    assert(cls.methods.contains("ping(Ljava/lang/String;)Ljava/lang/String;"))

    val module = readMembers(classes.get("foo/use/O$"))
    assert(module.methods.contains("ping(Ljava/lang/String;)Ljava/lang/String;"))

    val mirror = readMembers(classes.get("foo/use/O"))
    assert(mirror.methods.contains("ping(Ljava/lang/String;)Ljava/lang/String;"))
    assert((mirror.methods("ping(Ljava/lang/String;)Ljava/lang/String;") & Opcodes.ACC_STATIC) != 0)
  }

  test("parent-resolution-falls-back-to-local-package-when-wildcard-parent-unknown") {
    val source =
      List(
        "package foo.use",
        "import bar.pkg._",
        "class C extends Base {}",
      ).mkString("\n")

    val resolver = new ScalaLower.ParentKindResolver {
      override def isInterface(binaryName: String): Boolean = false

      override def superName(binaryName: String): String =
        binaryName match {
          case "foo/use/Base" => "java/lang/Object"
          case _ => null
        }
    }

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(
        ImmutableList.of(unit),
        LanguageVersion.createDefault().majorVersion(),
        resolver,
      )

    val header = readClassHeader(classes.get("foo/use/C"))
    assertEquals(header.superName, "foo/use/Base")
    assertEquals(header.interfaces, Nil)
  }

  test("class-parent-prefers-java-lang-illegal-argument-exception") {
    val source =
      List(
        "package foo",
        "class C extends IllegalArgumentException",
      ).mkString("\n")

    val resolver = new ScalaLower.ParentKindResolver {
      override def isInterface(binaryName: String): Boolean = false

      override def superName(binaryName: String): String =
        binaryName match {
          case "foo/IllegalArgumentException" => "java/lang/Object"
          case "java/lang/IllegalArgumentException" => "java/lang/RuntimeException"
          case _ => null
        }
    }

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(
        ImmutableList.of(unit),
        LanguageVersion.createDefault().majorVersion(),
        resolver,
      )

    val header = readClassHeader(classes.get("foo/C"))
    assertEquals(header.superName, "java/lang/IllegalArgumentException")
    assertEquals(header.interfaces, Nil)
  }

  test("class-parent-prefers-java-lang-unsupported-operation-exception") {
    val source =
      List(
        "package foo",
        "class C extends UnsupportedOperationException",
      ).mkString("\n")

    val resolver = new ScalaLower.ParentKindResolver {
      override def isInterface(binaryName: String): Boolean = false

      override def superName(binaryName: String): String =
        binaryName match {
          case "foo/UnsupportedOperationException" => "java/lang/Object"
          case "java/lang/UnsupportedOperationException" => "java/lang/RuntimeException"
          case _ => null
        }
    }

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(
        ImmutableList.of(unit),
        LanguageVersion.createDefault().majorVersion(),
        resolver,
      )

    val header = readClassHeader(classes.get("foo/C"))
    assertEquals(header.superName, "java/lang/UnsupportedOperationException")
    assertEquals(header.interfaces, Nil)
  }

  test("class-parent-prefers-scala-math-ordering") {
    val source =
      List(
        "package foo",
        "class C extends Ordering[Int]",
      ).mkString("\n")

    val resolver = new ScalaLower.ParentKindResolver {
      override def isInterface(binaryName: String): Boolean =
        binaryName == "scala/math/Ordering"

      override def superName(binaryName: String): String =
        binaryName match {
          case "foo/Ordering" => "java/lang/Object"
          case _ => null
        }
    }

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(
        ImmutableList.of(unit),
        LanguageVersion.createDefault().majorVersion(),
        resolver,
      )

    val header = readClassHeader(classes.get("foo/C"))
    assertEquals(header.superName, "java/lang/Object")
    assertEquals(header.interfaces, List("scala/math/Ordering"))
  }

  test("class-parent-prefers-package-object-member-binary") {
    val packageObjectSource =
      List(
        "package foo",
        "package object bar {",
        "  abstract class Projection",
        "}",
      ).mkString("\n")

    val classSource =
      List(
        "package foo.bar",
        "abstract class UnsafeProjection extends Projection",
      ).mkString("\n")

    val resolver = new ScalaLower.ParentKindResolver {
      override def isInterface(binaryName: String): Boolean = false

      override def superName(binaryName: String): String =
        binaryName match {
          case "foo/bar/Projection" => "java/lang/Object"
          case "foo/bar/package$Projection" => "java/lang/Object"
          case _ => null
        }
    }

    val packageObjectUnit = ScalaParser.parse(new SourceFile(null, packageObjectSource))
    val classUnit = ScalaParser.parse(new SourceFile(null, classSource))
    val classes =
      ScalaLower.lower(
        ImmutableList.of(packageObjectUnit, classUnit),
        LanguageVersion.createDefault().majorVersion(),
        resolver,
      )

    val header = readClassHeader(classes.get("foo/bar/UnsafeProjection"))
    assertEquals(header.superName, "foo/bar/package$Projection")
    assertEquals(header.interfaces, Nil)
  }

  test("parent-wildcard-import-beats-speculative-current-package-fallback") {
    val source =
      List(
        "package foo.target {",
        "  trait WrappedMessage",
        "}",
        "package foo.use {",
        "  import foo.target._",
        "  class C extends WrappedMessage",
        "}",
      ).mkString("\n")

    val resolver = new ScalaLower.ParentKindResolver {
      override def isInterface(binaryName: String): Boolean =
        binaryName == "foo/target/WrappedMessage"

      override def superName(binaryName: String): String =
        binaryName match {
          case "foo/use/WrappedMessage" => "java/lang/Object"
          case _ => null
        }
    }

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(
        ImmutableList.of(unit),
        LanguageVersion.createDefault().majorVersion(),
        resolver,
      )

    val header = readClassHeader(classes.get("foo/use/C"))
    assertEquals(header.superName, "java/lang/Object")
    assertEquals(header.interfaces, List("foo/target/WrappedMessage"))
  }

  test("class-constructor-parses-newline-separated-parameter-lists") {
    val source =
      List(
        "package foo",
        "class InputDStream[T]",
        "class ClassTag[T]",
        "class JavaDStream[T](val dstream: InputDStream[T])(implicit val classTag: ClassTag[T])",
        "class JavaInputDStream[T](val inputDStream: InputDStream[T])",
        "  (implicit override val classTag: ClassTag[T]) extends JavaDStream[T](inputDStream)",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val header = readClassHeader(classes.get("foo/JavaInputDStream"))
    assertEquals(header.superName, "foo/JavaDStream")
    assertEquals(header.interfaces, Nil)

    val cls = readMembers(classes.get("foo/JavaInputDStream"))
    assert(cls.methods.contains("<init>(Lfoo/InputDStream;Lfoo/ClassTag;)V"))
    assert(cls.methods.contains("classTag()Lfoo/ClassTag;"))
  }

  test("qualified-java-scala-types-ignore-package-wildcard-imports") {
    val source =
      List(
        "package foo",
        "class Helper",
        "trait T {",
        "  import foo._",
        "  def f(x: scala.Option, y: java.lang.Object): scala.collection.Iterator",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/T"))
    assert(cls.methods.contains("f(Lscala/Option;Ljava/lang/Object;)Lscala/collection/Iterator;"))
  }

  test("unqualified-throwable-resolves-to-java-lang") {
    val source =
      List(
        "package foo",
        "class C {",
        "  def f(e: Throwable): Throwable = e",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/C"))
    assert(cls.methods.contains("f(Ljava/lang/Throwable;)Ljava/lang/Throwable;"))
  }

  test("trait-impl-class") {
    val source =
      List(
        "package foo",
        "trait T {",
        "  def abstractDef(x: Int): Int",
        "  def concreteDef(x: Int): Int = x",
        "  val abstractVal: String",
        "  val concreteVal: String = \"ok\"",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    assert(classes.containsKey("foo/T$class"))
    val impl = readMembers(classes.get("foo/T$class"))
    assert(impl.methods.contains("$init$(Lfoo/T;)V"))
    assert(impl.methods.contains("concreteDef(Lfoo/T;I)I"))
    assert(impl.methods.contains("concreteVal(Lfoo/T;)Ljava/lang/String;"))
    assert(!impl.methods.contains("abstractDef(Lfoo/T;I)I"))
  }

  test("emits-generic-signatures") {
    val source =
      List(
        "package foo",
        "class Box[T](val x: T) {",
        "  def id[U](u: U): T = x",
        "}",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val sigs = readSignatures(classes.get("foo/Box"))
    assertEquals(sigs.classSignature, "<T:Ljava/lang/Object;>Ljava/lang/Object;")
    assertEquals(
      sigs.methodSignatures("id(Ljava/lang/Object;)Ljava/lang/Object;"),
      "<U:Ljava/lang/Object;>(TU;)TT;",
    )
    assertEquals(sigs.methodSignatures("x()Ljava/lang/Object;"), "()TT;")
  }

  private def readMembers(bytes: Array[Byte]): ClassMembers = {
    val members = new ClassMembers
    new ClassReader(bytes)
      .accept(
        new ClassVisitor(Opcodes.ASM9) {
          override def visitMethod(
              access: Int,
              name: String,
              descriptor: String,
              signature: String,
              exceptions: Array[String],
          ): MethodVisitor = {
            members.methods.put(name + descriptor, access)
            members.methodExceptions.put(name + descriptor, Option(exceptions).map(_.toList).getOrElse(Nil))
            null
          }

          override def visitField(
              access: Int,
              name: String,
              descriptor: String,
              signature: String,
              value: Object,
          ): FieldVisitor = {
            members.fields.put(name + descriptor, access)
            null
          }
        },
        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES,
      )
    members
  }

  private class ClassMembers {
    val methods: mutable.Map[String, Int] = mutable.Map.empty
    val methodExceptions: mutable.Map[String, List[String]] = mutable.Map.empty
    val fields: mutable.Map[String, Int] = mutable.Map.empty
  }

  private def readClassHeader(bytes: Array[Byte]): ClassHeader = {
    val header = new ClassHeader
    new ClassReader(bytes)
      .accept(
        new ClassVisitor(Opcodes.ASM9) {
          override def visit(
              version: Int,
              access: Int,
              name: String,
              signature: String,
              superName: String,
              interfaces: Array[String],
          ): Unit = {
            header.access = access
            header.superName = superName
            header.interfaces = interfaces.toList
          }
        },
        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES,
      )
    header
  }

  private class ClassHeader {
    var access: Int = 0
    var superName: String = _
    var interfaces: List[String] = Nil
  }

  private def readSignatures(bytes: Array[Byte]): ClassSignatures = {
    val signatures = new ClassSignatures
    new ClassReader(bytes)
      .accept(
        new ClassVisitor(Opcodes.ASM9) {
          override def visit(
              version: Int,
              access: Int,
              name: String,
              signature: String,
              superName: String,
              interfaces: Array[String],
          ): Unit = {
            signatures.classSignature = signature
          }

          override def visitMethod(
              access: Int,
              name: String,
              descriptor: String,
              signature: String,
              exceptions: Array[String],
          ): MethodVisitor = {
            signatures.methodSignatures.put(name + descriptor, signature)
            null
          }
        },
        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES,
      )
    signatures
  }

  private class ClassSignatures {
    var classSignature: String = _
    val methodSignatures: mutable.Map[String, String] = mutable.Map.empty
  }
}
