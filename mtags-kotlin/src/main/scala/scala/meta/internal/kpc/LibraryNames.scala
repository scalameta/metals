package scala.meta.internal.kpc

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.Try
import scala.util.Using

import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes

/**
 * Names of top-level declarations in the classpath, by package.
 *
 * The Analysis API finds a library declaration only by its exact name, so
 * completion takes the candidate names from here.
 *
 * ponytail: reads class files of `*Kt` facades only, so typealiases
 * (`kotlin.collections.ArrayList`) and facades renamed with `@JvmName` to a
 * name without `Kt` are missing. Read the Kotlin metadata if that matters.
 */
final class LibraryNames(classpath: Seq[Path]) {

  def apply(pkg: String): Set[String] = byPackage.getOrElse(pkg, Set.empty)

  private lazy val byPackage: Map[String, Set[String]] = {
    val names = mutable.Map.empty[String, mutable.Set[String]]
    def add(entry: String, bytes: => Array[Byte]): Unit = {
      val slash = entry.lastIndexOf('/')
      val simpleName = entry.substring(slash + 1).stripSuffix(".class")
      if (!simpleName.contains('$') && !entry.startsWith("META-INF/")) {
        val pkg =
          if (slash < 0) "" else entry.substring(0, slash).replace('/', '.')
        val set = names.getOrElseUpdate(pkg, mutable.Set.empty)
        set += simpleName
        if (simpleName.contains("Kt")) Try(set ++= members(bytes))
      }
    }
    for (root <- classpath) {
      if (Files.isDirectory(root))
        Using.resource(Files.walk(root)) { stream =>
          for (file <- stream.iterator.asScala if isClass(file.toString))
            add(
              root.relativize(file).toString.replace('\\', '/'),
              Files.readAllBytes(file)
            )
        }
      else if (Files.isRegularFile(root))
        Try(Using.resource(new ZipFile(root.toFile)) { zip =>
          for (entry <- zip.entries.asScala if isClass(entry.getName))
            add(
              entry.getName,
              Using.resource(zip.getInputStream(entry))(_.readAllBytes())
            )
        })
    }
    names.map { case (pkg, set) => pkg -> set.toSet }.toMap
  }

  private def isClass(name: String): Boolean = name.endsWith(".class")

  /**
   * Static methods, and the property names of getters and setters. An
   * `@InlineOnly` function is private in the class file, so the caller
   * filters by the Kotlin visibility.
   */
  private def members(bytes: Array[Byte]): Seq[String] = {
    val result = mutable.ListBuffer.empty[String]
    val visitor = new ClassVisitor(Opcodes.ASM9) {
      override def visitMethod(
          access: Int,
          name: String,
          descriptor: String,
          signature: String,
          exceptions: Array[String]
      ): MethodVisitor = {
        if (
          (access & Opcodes.ACC_STATIC) != 0 &&
          (access & Opcodes.ACC_SYNTHETIC) == 0
        ) {
          // Inline class parameters add a `-hash` suffix to the JVM name.
          val method = name.takeWhile(_ != '-')
          if (!method.contains('$') && !method.startsWith("<")) {
            result += method
            for {
              accessor <- List("get", "set", "is")
              if method.length > accessor.length && method.startsWith(accessor)
            } result += decapitalize(method.stripPrefix(accessor))
          }
        }
        null
      }
    }
    new ClassReader(bytes).accept(
      visitor,
      ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES
    )
    result.toList
  }

  private def decapitalize(name: String): String =
    name.head.toLower.toString + name.tail
}
