package com.google.turbine.main

import com.google.common.collect.ImmutableList
import com.google.turbine.testing.AsmUtils
import com.google.turbine.testing.TestClassPaths
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.LinkedHashMap
import java.util.TreeMap
import java.util.jar.JarFile
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.StandardLocation
import javax.tools.ToolProvider
import munit.FunSuite
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.tools.nsc.Global
import scala.tools.nsc.Settings
import scala.tools.nsc.reporters.StoreReporter

class ScalaConformanceSuite extends FunSuite {
  test("mixed-scala-java-conforms") {
    val dir = Files.createTempDirectory("turbine-scala-conformance")
    val codebase = Codebase.writeTo(dir)

    val turbineJar = dir.resolve("turbine-out.jar")
    val options = TestClassPaths.optionsWithBootclasspath()
    val sources = ImmutableList.copyOf(codebase.allSources.map(_.toString).asJava)
    options.setSources(sources)
    options.setOutput(turbineJar.toString)

    Main.compile(options.build())

    val turbineClasses = readJarClasses(turbineJar)

    val scalaLibrary = findScalaLibraryJar()
    assert(scalaLibrary.nonEmpty, "scala-library.jar not found on classpath")

    val javaOut = dir.resolve("javac-out")
    val scalaOut = dir.resolve("scalac-out")
    Files.createDirectories(javaOut)
    Files.createDirectories(scalaOut)

    compileJavac(codebase.javaPreSources, javaOut, Nil)
    compileScalac(codebase.scalaSources, scalaOut, List(javaOut))
    compileJavac(
      codebase.javaPostSources,
      javaOut,
      List(javaOut, scalaOut, scalaLibrary.get),
    )

    val baseline = new LinkedHashMap[String, Array[Byte]]()
    baseline.putAll(readDirClasses(javaOut).asJava)
    baseline.putAll(readDirClasses(scalaOut).asJava)

    assertSubset("turbine", turbineClasses, "baseline", baseline.asScala.toMap)
  }

  private def findScalaLibraryJar(): Option[Path] = {
    val classpath = Option(System.getProperty("java.class.path")).getOrElse("")
    classpath
      .split(File.pathSeparator)
      .find(path => path.contains("scala-library") && path.endsWith(".jar"))
      .map(Paths.get(_))
      .filter(path => Files.exists(path))
  }

  private def compileScalac(sources: List[Path], out: Path, classpath: List[Path]): Unit = {
    if (sources.isEmpty) {
      return
    }
    val baseCp = Option(System.getProperty("java.class.path")).getOrElse("")
    val cpEntries =
      (baseCp.split(File.pathSeparator).filter(_.nonEmpty).toList ++ classpath.map(_.toString))
        .distinct
        .mkString(File.pathSeparator)

    val settings = new Settings()
    settings.classpath.value = cpEntries
    settings.outputDirs.setSingleOutput(out.toString)

    val reporter = new StoreReporter
    val global = new Global(settings, reporter)
    val run = new global.Run
    run.compile(sources.map(_.toString))

    if (reporter.hasErrors) {
      val messages = reporter.infos.map(info => info.toString).mkString("\n")
      throw new AssertionError(s"scalac failed:\n$messages")
    }
  }

  private def compileJavac(sources: List[Path], out: Path, classpath: List[Path]): Unit = {
    if (sources.isEmpty) {
      return
    }
    val compiler = ToolProvider.getSystemJavaCompiler
    if (compiler == null) {
      throw new AssertionError("No system Java compiler available")
    }
    val collector = new DiagnosticCollector[JavaFileObject]()
    val fileManager = compiler.getStandardFileManager(collector, null, null)
    try {
      fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List(out.toFile).asJava)
      if (classpath.nonEmpty) {
        fileManager.setLocation(
          StandardLocation.CLASS_PATH,
          classpath.map(_.toFile).asJava,
        )
      }

      val task =
        compiler.getTask(
          null,
          fileManager,
          collector,
          null,
          null,
          fileManager.getJavaFileObjectsFromPaths(sources.asJava),
        )
      val ok = task.call()
      if (!ok) {
        val diagnostics = collector.getDiagnostics.asScala.map(_.toString).mkString("\n")
        throw new AssertionError(s"javac failed:\n$diagnostics")
      }
    } finally {
      fileManager.close()
    }
  }

  private def readJarClasses(jar: Path): Map[String, Array[Byte]] = {
    val out = new LinkedHashMap[String, Array[Byte]]()
    val jarFile = new JarFile(jar.toFile)
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
    out.asScala.toMap
  }

  private def readDirClasses(root: Path): Map[String, Array[Byte]] = {
    val out = new LinkedHashMap[String, Array[Byte]]()
    if (!Files.exists(root)) {
      return out.asScala.toMap
    }
    Files
      .walk(root)
      .filter(path => path.toString.endsWith(".class"))
      .forEach { path =>
        val rel = root.relativize(path).toString
        val name = rel.substring(0, rel.length - ".class".length).replace(File.separatorChar, '/')
        out.put(name, Files.readAllBytes(path))
      }
    out.asScala.toMap
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
      sb.append(AsmUtils.textify(bytes, skipDebug = true)).append('\n')
    }
    actual.get(name).foreach { bytes =>
      sb.append(s"=== $actualLabel ===\n")
      sb.append(AsmUtils.textify(bytes, skipDebug = true)).append('\n')
    }
    sb.toString()
  }

  private def toClassInfos(classes: Map[String, Array[Byte]]): Map[String, ClassInfo] = {
    val out = mutable.LinkedHashMap.empty[String, ClassInfo]
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
    def from(visible: java.util.List[AnnotationNode], invisible: java.util.List[AnnotationNode]): AnnotationSet = {
      AnnotationSet(collect(visible), collect(invisible))
    }

    private def collect(annos: java.util.List[AnnotationNode]): List[String] = {
      if (annos == null || annos.isEmpty) {
        return Nil
      }
      val out = annos.asScala.map(_.desc).toList.sorted
      out
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
      val ifaces = Option(node.interfaces).map(_.asScala.toList).getOrElse(Nil).sorted
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

  private final case class Codebase(
      javaPreSources: List[Path],
      scalaSources: List[Path],
      javaPostSources: List[Path],
      allSources: List[Path],
  )

  private object Codebase {
    def writeTo(root: Path): Codebase = {
      val javaPre = List(
        SourceSpec(
          "example/jpre/JGreeter.java",
          List(
            "package example.jpre;",
            "public interface JGreeter {",
            "  String greet(String name);",
            "}",
            "",
          ).mkString("\n"),
        ),
        SourceSpec(
          "example/jpre/JBase.java",
          List(
            "package example.jpre;",
            "public class JBase {",
            "  public final int id;",
            "  public JBase(int id) { this.id = id; }",
            "}",
            "",
          ).mkString("\n"),
        ),
      )

      val scala = List(
        SourceSpec(
          "example/sca/Models.scala",
          List(
            "package example.sca",
            "",
            "import example.jpre.JBase",
            "import example.jpre.JGreeter",
            "import java.util.List",
            "",
            "class Box(val x: Int, var name: String)",
            "",
            "trait Greeter extends JGreeter {",
            "  val prefix: String",
            "  def greet(name: String): String",
            "}",
            "",
            "case class Pair[A <: AnyRef](left: A, right: A)",
            "",
            "class UsesJava(val base: JBase) {",
            "  def id(x: Int, y: String = \"ok\"): String = y",
            "  def curried(a: Int)(b: String): String = b",
            "  type Alias = List[String]",
            "}",
          ).mkString("\n"),
        ),
        SourceSpec(
          "example/sca/PackageStuff.scala",
          List(
            "package example.sca",
            "",
            "package object pkg {",
            "  val pkgVal: String = \"ok\"",
            "  def pkgFun(x: Int = 1): String = String.valueOf(x)",
            "}",
            "",
          ).mkString("\n"),
        ),
      )

      val javaPost = List(
        SourceSpec(
          "example/jpost/UseScala.java",
          List(
            "package example.jpost;",
            "",
            "import example.sca.Box;",
            "import example.sca.pkg.package$;",
            "",
            "public class UseScala {",
            "  public int read(Box b) { return b.x(); }",
            "  public void rename(Box b, String n) { b.name_$eq(n); }",
            "  public String pkgFun() { return package$.MODULE$.pkgFun(2); }",
            "}",
            "",
          ).mkString("\n"),
        ),
      )

      val javaPrePaths = writeAll(root, javaPre)
      val scalaPaths = writeAll(root, scala)
      val javaPostPaths = writeAll(root, javaPost)
      val all = javaPrePaths ++ scalaPaths ++ javaPostPaths
      Codebase(javaPrePaths, scalaPaths, javaPostPaths, all)
    }

    private def writeAll(root: Path, specs: List[SourceSpec]): List[Path] = {
      specs.map { spec =>
        val path = root.resolve(spec.relativePath)
        Files.createDirectories(path.getParent)
        Files.writeString(path, spec.contents, StandardCharsets.UTF_8)
        path
      }
    }
  }

  private final case class SourceSpec(relativePath: String, contents: String)
}
