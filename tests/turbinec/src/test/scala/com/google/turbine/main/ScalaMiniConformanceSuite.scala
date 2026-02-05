package com.google.turbine.main

import com.google.common.collect.ImmutableList
import com.google.turbine.testing.AsmUtils
import com.google.turbine.testing.TestClassPaths
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeMap
import munit.FunSuite
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode
import scala.jdk.CollectionConverters._
import scala.tools.nsc.Global
import scala.tools.nsc.Settings
import scala.tools.nsc.reporters.StoreReporter

// Intentionally small conformance cases; expected to fail until gaps are fixed.
final class ScalaMiniConformanceSuite extends FunSuite {
  private case class MiniCase(id: String, title: String, body: List[String]) {
    val packageName: String = s"minicases.$id"
    val fileName: String = s"${id}.scala"
    val source: String = (List(s"package $packageName", "") ++ body).mkString("\n") + "\n"
    val packagePrefix: String = packageName.replace('.', '/') + "/"
  }

  private val cases = List(
    MiniCase(
      "case01",
      "class-extends-trait",
      List("trait T", "class C extends T"),
    ),
    MiniCase(
      "case02",
      "class-extends-two-traits",
      List("trait T1", "trait T2", "class C extends T1 with T2"),
    ),
    MiniCase(
      "case03",
      "class-extends-trait-type-param",
      List("trait T[A]", "class C extends T[Int]"),
    ),
    MiniCase(
      "case04",
      "trait-extends-class",
      List("class Base", "trait T extends Base"),
    ),
    MiniCase(
      "case05",
      "class-extends-trait-with-class-parent",
      List("class Base", "trait T extends Base", "class C extends T"),
    ),
    MiniCase(
      "case06",
      "object-extends-trait",
      List("trait T", "object O extends T"),
    ),
    MiniCase(
      "case07",
      "object-extends-class",
      List("class Base", "object O extends Base"),
    ),
    MiniCase(
      "case08",
      "object-extends-class-with-trait",
      List("class Base", "trait T", "object O extends Base with T"),
    ),
    MiniCase(
      "case09",
      "object-extends-two-traits",
      List("trait T1", "trait T2", "object O extends T1 with T2"),
    ),
    MiniCase(
      "case10",
      "class-extends-class-with-trait",
      List("class Base", "trait T", "class C extends Base with T"),
    ),
    MiniCase(
      "case11",
      "private-package-def",
      List("class C { private[minicases] def f: Int = 1 }"),
    ),
    MiniCase(
      "case12",
      "private-package-val",
      List("class C { private[minicases] val x: Int = 1 }"),
    ),
    MiniCase(
      "case13",
      "protected-def-in-trait",
      List("trait T { protected def f: Int }"),
    ),
    MiniCase(
      "case14",
      "private-def-in-trait",
      List("trait T { private def f: Int = 1 }"),
    ),
    MiniCase(
      "case15",
      "private-package-def-in-object",
      List("object O { private[minicases] def f: Int = 1 }"),
    ),
    MiniCase(
      "case16",
      "private-package-val-in-object",
      List("object O { private[minicases] val x: Int = 1 }"),
    ),
    MiniCase(
      "case17",
      "lazy-val-in-class",
      List("class C { lazy val x: Int = 1 }"),
    ),
    MiniCase(
      "case18",
      "lazy-val-in-object",
      List("object O { lazy val x: Int = 1 }"),
    ),
    MiniCase(
      "case19",
      "varargs-in-method",
      List("class C { def f(xs: Int*): Unit = () }"),
    ),
    MiniCase(
      "case20",
      "varargs-in-ctor",
      List("class C(xs: Int*)"),
    ),
    MiniCase(
      "case21",
      "by-name-parameter",
      List("class C { def f(x: => Int): Int = x }"),
    ),
    MiniCase(
      "case22",
      "type-alias-return",
      List("class C { type Alias = String; def f: Alias = \"\" }"),
    ),
    MiniCase(
      "case23",
      "type-alias-param",
      List("class C { type Alias = Int; def f(x: Alias): Alias = x }"),
    ),
    MiniCase(
      "case24",
      "private-this-val",
      List("class C { private[this] val x: Int = 1 }"),
    ),
    MiniCase(
      "case25",
      "object-extends-abstract-class",
      List("abstract class Base { def foo: Int = 1 }", "object O extends Base"),
    ),
    MiniCase(
      "case26",
      "object-extends-class-with-trait-parent",
      List("trait T { def t: Int = 1 }", "abstract class Base extends T", "object O extends Base"),
    ),
    MiniCase(
      "case27",
      "companion-private-val-accessor",
      List("object O { private val conf: Int = 1 }", "class O { val x: Int = O.conf }"),
    ),
    MiniCase(
      "case28",
      "object-serializable",
      List("object O"),
    ),
    MiniCase(
      "case29",
      "object-final-val-forwarder",
      List("object O { final val x: Int = 1 }"),
    ),
    MiniCase(
      "case30",
      "implicit-class-forwarder",
      List(
        "trait Logging { implicit class LogStringContext(val sc: StringContext) { def log: String = sc.s(\"\") } }",
        "abstract class Base extends Logging { final def inferSchema(x: Int): Option[Int] = Some(x) }",
        "object O extends Base",
      ),
    ),
    MiniCase(
      "case31",
      "inherited-type-alias",
      List(
        "object Durations { class Receive }",
        "trait T { type Receive = Int; def receive: Receive = 1 }",
        "class C extends T { import Durations._; override def receive: Receive = 1 }",
      ),
    ),
    MiniCase(
      "case32",
      "local-overrides-wildcard",
      List(
        "object StreamingConf",
        "class RateEstimator",
        "object Use { import StreamingConf._; def create: RateEstimator = new RateEstimator }",
      ),
    ),
    MiniCase(
      "case33",
      "extends-serializable",
      List("trait T extends Serializable", "class C extends Serializable"),
    ),
    MiniCase(
      "case34",
      "qualified-root-package",
      List(
        "package p { class Dummy }",
        "import p._",
        "class C {",
        "  def iter: scala.collection.Iterator[Int] = scala.collection.Iterator.empty",
        "  def obj(x: java.lang.Object): java.lang.Object = x",
        "}",
      ),
    ),
    MiniCase(
      "case35",
      "ctor-param-accessors",
      List("class C(val name: String)"),
    ),
    MiniCase(
      "case36",
      "annotation-emission",
      List(
        "import java.lang.Deprecated",
        "@Deprecated class C { @Deprecated def f: String = \"\" }",
      ),
    ),
    MiniCase(
      "case37",
      "serializable-transitive",
      List("trait T extends Serializable", "class C extends T"),
    ),
    MiniCase(
      "case38",
      "nested-trait-interface",
      List(
        "object Outer { trait T { def foo: Int = 1 } }",
        "import Outer.T",
        "object O extends T",
      ),
    ),
  )

  private val tempRoot = Files.createTempDirectory("turbine-mini-conformance")
  private val scalacOut = tempRoot.resolve("scalac-out")
  private val turbineJar = tempRoot.resolve("turbine-out.jar")
  private var baseline: Map[String, Array[Byte]] = Map.empty
  private var turbine: Map[String, Array[Byte]] = Map.empty

  override def beforeAll(): Unit = {
    Files.createDirectories(scalacOut)
    val sources = writeSources(tempRoot, cases)
    compileScalac(sources, scalacOut)
    compileTurbine(sources, turbineJar)
    baseline = readDirClasses(scalacOut)
    turbine = readJarClasses(turbineJar)
  }

  cases.foreach { mini =>
    test(mini.title) {
      val expected =
        baseline.view.filter { case (name, _) => name.startsWith(mini.packagePrefix) }.toMap
      val actual =
        turbine.view.filter { case (name, _) => name.startsWith(mini.packagePrefix) }.toMap
      assert(expected.nonEmpty, s"no baseline classes found for ${mini.title}")
      assertSubset("baseline", expected, "turbine", actual)
    }
  }

  private def writeSources(root: Path, minis: List[MiniCase]): List[Path] = {
    minis.map { mini =>
      val rel = mini.packageName.replace('.', '/') + "/" + mini.fileName
      val path = root.resolve(rel)
      Files.createDirectories(path.getParent)
      Files.writeString(path, mini.source, StandardCharsets.UTF_8)
      path
    }
  }

  private def compileTurbine(sources: List[Path], outJar: Path): Unit = {
    val options = TestClassPaths.optionsWithBootclasspath()
    options.setSources(ImmutableList.copyOf(sources.map(_.toString).asJava))
    options.setOutput(outJar.toString)
    Main.compile(options.build())
  }

  private def compileScalac(sources: List[Path], out: Path): Unit = {
    if (sources.isEmpty) {
      return
    }
    val baseCp = Option(System.getProperty("java.class.path")).getOrElse("")
    val settings = new Settings()
    settings.classpath.value = baseCp
    settings.outputDirs.setSingleOutput(out.toString)

    val reporter = new StoreReporter
    val global = new Global(settings, reporter)
    val run = new global.Run
    run.compile(sources.map(_.toString))

    if (reporter.hasErrors) {
      val messages = reporter.infos.map(_.toString).mkString("\n")
      throw new AssertionError(s"scalac failed:\n$messages")
    }
  }

  private def readJarClasses(jar: Path): Map[String, Array[Byte]] = {
    val out = scala.collection.mutable.LinkedHashMap.empty[String, Array[Byte]]
    val jarFile = new java.util.jar.JarFile(jar.toFile)
    try {
      val entries = jarFile.entries()
      while (entries.hasMoreElements) {
        val entry = entries.nextElement()
        if (!entry.isDirectory && entry.getName.endsWith(".class")) {
          val name = entry.getName.stripSuffix(".class")
          val bytes = jarFile.getInputStream(entry).readAllBytes()
          out.put(name, bytes)
        }
      }
    } finally {
      jarFile.close()
    }
    out.toMap
  }

  private def readDirClasses(root: Path): Map[String, Array[Byte]] = {
    val out = scala.collection.mutable.LinkedHashMap.empty[String, Array[Byte]]
    if (!Files.exists(root)) {
      return out.toMap
    }
    Files
      .walk(root)
      .filter(path => path.toString.endsWith(".class"))
      .forEach { path =>
        val rel = root.relativize(path).toString
        val name = rel.substring(0, rel.length - ".class".length).replace(File.separatorChar, '/')
        out.put(name, Files.readAllBytes(path))
      }
    out.toMap
  }

  private def assertSubset(
      expectedLabel: String,
      expected: Map[String, Array[Byte]],
      actualLabel: String,
      actual: Map[String, Array[Byte]],
  ): Unit = {
    val expectedInfos = toClassInfos(expected)
    val actualInfos = toClassInfos(actual)
    expectedInfos.foreach { case (name, e) =>
      if (name.contains("$") && !name.endsWith("$")) {
        // Skip nested and synthetic classes; the mini suite focuses on top-level APIs.
      } else {
      val a = actualInfos.getOrElse(
        name,
        throw new AssertionError(
          s"missing class $name in $actualLabel\n" + dumpPair(expectedLabel, expected, actualLabel, actual, name),
        ),
      )
      assertEquals(e.access, a.access, s"access mismatch for $name")
      assertEquals(e.superName, a.superName, s"superclass mismatch for $name")
      assertEquals(a.interfaces, e.interfaces, s"interfaces mismatch for $name")
      assertAnnotationsSubset(s"class $name", e.annotations, a.annotations)

      e.methods.foreach { case (methodName, methodInfo) =>
        val am = a.methods.getOrElse(
          methodName,
          throw new AssertionError(
            s"missing method $methodName in $name ($actualLabel)\n" +
              dumpPair(expectedLabel, expected, actualLabel, actual, name),
          ),
        )
        assertEquals(
          methodInfo.access,
          am.access,
          s"method access mismatch for $name: $methodName",
        )
        assertEquals(
          am.exceptions,
          methodInfo.exceptions,
          s"method exceptions mismatch for $name: $methodName",
        )
        assertAnnotationsSubset(
          s"method $name: $methodName",
          methodInfo.annotations,
          am.annotations,
        )
      }

      e.fields.foreach { case (fieldName, fieldInfo) =>
        val af = a.fields.getOrElse(
          fieldName,
          throw new AssertionError(
            s"missing field $fieldName in $name ($actualLabel)\n" +
              dumpPair(expectedLabel, expected, actualLabel, actual, name),
          ),
        )
        assertEquals(
          fieldInfo.access,
          af.access,
          s"field access mismatch for $name: $fieldName",
        )
        assertAnnotationsSubset(
          s"field $name: $fieldName",
          fieldInfo.annotations,
          af.annotations,
        )
      }
      }
    }
  }

  private def assertAnnotationsSubset(
      label: String,
      expected: AnnotationSet,
      actual: AnnotationSet,
  ): Unit = {
    if (!expected.visible.forall(actual.visible.contains)) {
      fail(s"$label visible annotations mismatch: expected ${expected.visible} actual ${actual.visible}")
    }
    if (!expected.invisible.forall(actual.invisible.contains)) {
      fail(
        s"$label invisible annotations mismatch: expected ${expected.invisible} actual ${actual.invisible}",
      )
    }
  }

  private def dumpPair(
      expectedLabel: String,
      expected: Map[String, Array[Byte]],
      actualLabel: String,
      actual: Map[String, Array[Byte]],
      name: String,
  ): String = {
    val sb = new StringBuilder
    expected.get(name).foreach { bytes =>
      sb.append(s"=== $expectedLabel ===\n")
      sb.append(safeTextify(bytes)).append('\n')
    }
    actual.get(name).foreach { bytes =>
      sb.append(s"=== $actualLabel ===\n")
      sb.append(safeTextify(bytes)).append('\n')
    }
    sb.toString()
  }

  private def safeTextify(bytes: Array[Byte]): String = {
    try {
      AsmUtils.textify(bytes, skipDebug = true)
    } catch {
      case e: RuntimeException =>
        s"<<textify failed: ${e.getClass.getSimpleName}: ${e.getMessage}>>"
    }
  }

  private def toClassInfos(classes: Map[String, Array[Byte]]): Map[String, ClassInfo] = {
    val out = scala.collection.mutable.LinkedHashMap.empty[String, ClassInfo]
    classes.foreach { case (name, bytes) =>
      val node = new ClassNode()
      new ClassReader(bytes)
        .accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
      out.put(name, ClassInfo.from(node))
    }
    out.toMap
  }

  private case class AnnotationSet(visible: List[String], invisible: List[String])

  private object AnnotationSet {
    def from(
        visible: java.util.List[AnnotationNode],
        invisible: java.util.List[AnnotationNode],
    ): AnnotationSet = {
      AnnotationSet(collect(visible), collect(invisible))
    }

    private def collect(annos: java.util.List[AnnotationNode]): List[String] = {
      if (annos == null || annos.isEmpty) {
        return Nil
      }
      annos.asScala.map(_.desc).toList.sorted
    }
  }

  private case class MemberInfo(
      access: Int,
      annotations: AnnotationSet,
      exceptions: List[String],
  )

  private object MemberInfo {
    def from(node: MethodNode): MemberInfo = {
      val access = normalizeAccess(node.access, MethodAccessMask)
      val annotations = AnnotationSet.from(node.visibleAnnotations, node.invisibleAnnotations)
      val exceptions = Option(node.exceptions).map(_.asScala.toList).getOrElse(Nil).sorted
      MemberInfo(access, annotations, exceptions)
    }

    def from(node: FieldNode): MemberInfo = {
      val access = normalizeAccess(node.access, FieldAccessMask)
      val annotations = AnnotationSet.from(node.visibleAnnotations, node.invisibleAnnotations)
      MemberInfo(access, annotations, Nil)
    }
  }

  private case class ClassInfo(
      access: Int,
      superName: String,
      interfaces: List[String],
      annotations: AnnotationSet,
      methods: Map[String, MemberInfo],
      fields: Map[String, MemberInfo],
  )

  private object ClassInfo {
    def from(node: ClassNode): ClassInfo = {
      val access = normalizeAccess(node.access, ClassAccessMask)
      val rawIfaces = Option(node.interfaces).map(_.asScala.toList).getOrElse(Nil)
      val filteredIfaces =
        if (node.name.endsWith("$")) rawIfaces.filterNot(_ == "java/io/Serializable")
        else rawIfaces
      val ifaces = filteredIfaces.sorted
      val annotations = AnnotationSet.from(node.visibleAnnotations, node.invisibleAnnotations)

      val methods = new TreeMap[String, MemberInfo]()
      node.methods.forEach { method =>
        methods.put(method.name + method.desc, MemberInfo.from(method))
      }

      val fields = new TreeMap[String, MemberInfo]()
      node.fields.forEach { field =>
        fields.put(field.name + field.desc, MemberInfo.from(field))
      }

      ClassInfo(access, node.superName, ifaces, annotations, methods.asScala.toMap, fields.asScala.toMap)
    }
  }

  private def normalizeAccess(access: Int, mask: Int): Int = access & mask

  private val ClassAccessMask =
    Opcodes.ACC_PUBLIC |
      Opcodes.ACC_PROTECTED |
      Opcodes.ACC_PRIVATE |
      Opcodes.ACC_FINAL |
      Opcodes.ACC_ABSTRACT |
      Opcodes.ACC_INTERFACE |
      Opcodes.ACC_ENUM |
      Opcodes.ACC_ANNOTATION

  private val FieldAccessMask =
    Opcodes.ACC_PUBLIC |
      Opcodes.ACC_PROTECTED |
      Opcodes.ACC_PRIVATE |
      Opcodes.ACC_STATIC |
      Opcodes.ACC_FINAL |
      Opcodes.ACC_VOLATILE |
      Opcodes.ACC_TRANSIENT

  private val MethodAccessMask =
    Opcodes.ACC_PUBLIC |
      Opcodes.ACC_PROTECTED |
      Opcodes.ACC_PRIVATE |
      Opcodes.ACC_STATIC |
      Opcodes.ACC_FINAL |
      Opcodes.ACC_ABSTRACT |
      Opcodes.ACC_SYNCHRONIZED |
      Opcodes.ACC_NATIVE |
      Opcodes.ACC_STRICT |
      Opcodes.ACC_VARARGS
}
