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
    assert(module.methods.contains("fromProduct(Lscala/Product;)Lscala/deriving/Mirror$Singleton;"))

    val mirror = readMembers(classes.get("foo/Solo"))
    assert(mirror.methods.contains("productArity()I"))
    assert((mirror.methods("productArity()I") & Opcodes.ACC_STATIC) != 0)
    assert(mirror.methods.contains("fromProduct(Lscala/Product;)Lscala/deriving/Mirror$Singleton;"))
    assert(
      (mirror.methods("fromProduct(Lscala/Product;)Lscala/deriving/Mirror$Singleton;") & Opcodes.ACC_STATIC) != 0
    )
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

  test("resolves-selector-import-renames") {
    val source =
      List(
        "package foo",
        "import java.util.{List => JList, _}",
        "class C(val xs: JList, val ys: ArrayList)",
      ).mkString("\n")

    val unit = ScalaParser.parse(new SourceFile(null, source))
    val classes =
      ScalaLower.lower(ImmutableList.of(unit), LanguageVersion.createDefault().majorVersion())

    val cls = readMembers(classes.get("foo/C"))
    assert(cls.methods.contains("xs()Ljava/util/List;"))
    assert(cls.methods.contains("ys()Ljava/util/ArrayList;"))
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
    assertEquals(sigs.classSignature, "<T:>Ljava/lang/Object;")
    assertEquals(
      sigs.methodSignatures("id(Ljava/lang/Object;)Ljava/lang/Object;"),
      "<U:>(TU;)TT;",
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
    val fields: mutable.Map[String, Int] = mutable.Map.empty
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
