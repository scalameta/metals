package tests.turbinec

import com.google.common.collect.ImmutableList
import com.google.turbine.diag.SourceFile
import com.google.turbine.diag.TurbineError
import com.google.turbine.main.Main
import com.google.turbine.options.LanguageVersion
import com.google.turbine.scalaparse.ScalaParser
import com.google.turbine.testing.IntegrationTestSupport
import com.google.turbine.testing.TestClassPaths
import java.nio.file.Files
import java.nio.file.Path
import java.util.LinkedHashMap
import java.util.TreeMap
import java.util.jar.JarFile
import munit.FunSuite
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode
import scala.collection.mutable
import scala.jdk.CollectionConverters._

class ScalaOutlineInputCompatSuite extends FunSuite {
  test("scala-outline-input-compat") {
    val input = InputProperties.scala2()
    val scalaSources = input.scala2Sources
    val (parseableSources, skippedSources) = scalaSources.partition(isParseable)
    if (skippedSources.nonEmpty) {
      println(s"Skipping ${skippedSources.size} Scala sources that the parser can't handle yet:")
      skippedSources.foreach(path => println(s"  - $path"))
    }
    val javaSources = listSources(input.sourceDirectories, ".java")
    val sources = parseableSources ++ javaSources
    assert(sources.nonEmpty, "no parseable sources found for input tests")

    val outDir = Files.createTempDirectory("turbine-input-compat")
    val turbineJar = outDir.resolve("turbine.jar")

    val options = TestClassPaths.optionsWithBootclasspath()
    options.setLanguageVersion(
      LanguageVersion.fromJavacopts(ImmutableList.of("--release", "17")),
    )
    options.setSources(ImmutableList.copyOf(sources.map(_.toString).asJava))
    val jarClasspath = input.classpath.filter(path => Files.isRegularFile(path))
    options.setClassPath(ImmutableList.copyOf(jarClasspath.map(_.toString).asJava))
    options.setOutput(turbineJar.toString)

    Main.compile(options.build())

    val turbineClasses = readJarClasses(turbineJar)
    val filteredTurbine = turbineClasses.filterNot { case (name, _) => name.endsWith("$class") }
    val baseline = readBaselineFromClasspath(filteredTurbine.keySet.toList, input.classpath)

    val expected = IntegrationTestSupport.canonicalize(filteredTurbine)
    val actual = IntegrationTestSupport.canonicalize(baseline)

    assertSubset("turbine", expected, "baseline", actual)
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

  private def readBaselineFromClasspath(
      classNames: List[String],
      classpath: List[Path],
  ): Map[String, Array[Byte]] = {
    val jarCache = mutable.Map.empty[Path, JarFile]
    val out = mutable.LinkedHashMap.empty[String, Array[Byte]]
    try {
      classNames.foreach { name =>
        readClasspathClassBytes(classpath, name, jarCache) match {
          case Some(bytes) => out.put(name, bytes)
          case None =>
            fail(s"missing class $name in input classpath")
        }
      }
    } finally {
      jarCache.values.foreach(_.close())
    }
    out.toMap
  }

  private def readClasspathClassBytes(
      classpath: List[Path],
      className: String,
      jarCache: mutable.Map[Path, JarFile],
  ): Option[Array[Byte]] = {
    val entryName = s"$className.class"
    classpath.foreach { entry =>
      if (Files.isDirectory(entry)) {
        val candidate = entry.resolve(entryName)
        if (Files.exists(candidate)) {
          return Some(Files.readAllBytes(candidate))
        }
      } else if (entry.toString.endsWith(".jar") && Files.isRegularFile(entry)) {
        val jar = jarCache.getOrElseUpdate(entry, new JarFile(entry.toFile))
        val jarEntry = jar.getEntry(entryName)
        if (jarEntry != null) {
          return Some(jar.getInputStream(jarEntry).readAllBytes())
        }
      }
    }
    None
  }

  private def listSources(directories: List[Path], extension: String): List[Path] = {
    directories.flatMap { dir =>
      if (Files.isDirectory(dir)) {
        val stream = Files.walk(dir)
        try {
          stream
            .filter(path => path.toString.endsWith(extension))
            .iterator()
            .asScala
            .toList
        } finally {
          stream.close()
        }
      } else {
        Nil
      }
    }
  }

  private def isParseable(path: Path): Boolean = {
    val text = Files.readString(path)
    try {
      ScalaParser.parse(new SourceFile(path.toString, text))
      true
    } catch {
      case _: TurbineError => false
    }
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
          s"missing class $name in $actualLabel\n" +
            dumpPair(expectedLabel, expected, actualLabel, actual, name),
        ),
      )
      val isModuleClass = name.endsWith("$")
      assertEquals(e.access, a.access, s"access mismatch for $name")
      if (!isModuleClass || e.superName != "java/lang/Object") {
        assertEquals(e.superName, a.superName, s"superclass mismatch for $name")
      }
      if (isModuleClass) {
        assertInterfacesSubset(name, e.interfaces, a.interfaces)
      } else {
        assertEquals(a.interfaces, e.interfaces, s"interfaces mismatch for $name")
      }
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

  private def assertInterfacesSubset(
      className: String,
      expected: List[String],
      actual: List[String],
  ): Unit = {
    if (!expected.forall(actual.contains)) {
      fail(s"class $className interfaces mismatch: expected $expected actual $actual")
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
      sb.append(IntegrationTestSupport.dump(Map(name -> bytes))).append('\n')
    }
    actual.get(name).foreach { bytes =>
      sb.append(s"=== $actualLabel ===\n")
      sb.append(IntegrationTestSupport.dump(Map(name -> bytes))).append('\n')
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
}
