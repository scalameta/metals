package tests.j

import javax.tools.Diagnostic
import javax.tools.DiagnosticListener
import javax.tools.JavaCompiler
import javax.tools.JavaFileObject
import javax.tools.StandardJavaFileManager
import javax.tools.ToolProvider

import scala.collection.mutable.ArrayBuffer

import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.EmptyWorkDoneProgress
import scala.meta.internal.metals.MetalsEnrichments._

import com.sun.source.util.JavacTask
import munit.AnyFixture
import tests.FileLayout

class JavaStoreDiagnosticListener extends DiagnosticListener[JavaFileObject] {
  val diagnostics: ArrayBuffer[Diagnostic[_ <: JavaFileObject]] =
    ArrayBuffer.empty[Diagnostic[_ <: JavaFileObject]]
  override def report(diagnostic: Diagnostic[_ <: JavaFileObject]): Unit = {
    diagnostics += diagnostic
  }
}

class HeaderCompilerSuite extends munit.FunSuite {
  private val COMPILER: JavaCompiler = ToolProvider.getSystemJavaCompiler()
  val fileManager: StandardJavaFileManager =
    COMPILER.getStandardFileManager(null, null, null)
  val tmp = new tests.TemporaryDirectoryFixture()
  override def munitFixtures: Seq[AnyFixture[_]] = List(tmp)

  def compileFiles(
      layout: String,
      options: List[String] = List(),
  ): List[Diagnostic[_ <: JavaFileObject]] = {
    FileLayout.fromString(layout, root = tmp())
    val embedded = new Embedded(tmp(), EmptyWorkDoneProgress)
    val allOptions = List(
      "-classpath",
      embedded.javaHeaderCompiler.toNIO.toString,
    ) ++ options
    val store = new JavaStoreDiagnosticListener()
    val paths = tmp().listRecursive
      .map(_.toNIO)
      .filter(_.toString.endsWith(".java"))
      .toList
    val files = fileManager.getJavaFileObjectsFromPaths(paths.asJava)
    val task = COMPILER
      .getTask(
        null,
        fileManager,
        store,
        allOptions.asJava,
        null,
        files,
      )
      .asInstanceOf[JavacTask]
    task.analyze()
    store.diagnostics.toList
  }

  def checkNoErrors(name: munit.TestOptions, layout: String)(implicit
      loc: munit.Location
  ): Unit = {
    test(name) {
      val withPlugin =
        compileFiles(layout, options = List("-Xplugin:MetalsHeaderCompiler"))
      assert(clue(withPlugin).isEmpty)
      assert(clue(compileFiles(layout)).nonEmpty)
    }
  }

  def checkErrors(
      name: munit.TestOptions,
      layout: String,
      expectedDiagnostics: String,
  )(implicit loc: munit.Location): Unit = {
    test(name) {
      val obtainedDiagnostics =
        compileFiles(layout, options = List("-Xplugin:MetalsHeaderCompiler"))
      assertNoDiff(obtainedDiagnostics.mkString("\n"), expectedDiagnostics)
    }
  }

  checkNoErrors(
    "class",
    """|
       |/a/src/main/java/a/Example.java
       |package a;
       |
       |public class Example {
       | public final int myIntField = "42";
       | public final double myDoubleField = "42";
       | public final float myFloatField = "42";
       | public final long myLongField = "42";
       | public final short myShortField = "42";
       | public final byte myByteField = "42";
       | public final char myCharField = "42";
       | public final boolean myBooleanField = "42";
       | public final String myStringField = "42";
       | public final int myIntMethod() { return "42"; }
       | public final double myDoubleMethod() { return "42"; }
       | public final float myFloatMethod() { return "42"; }
       | public final long myLongMethod() { return "42"; }
       | public final short myShortMethod() { return "42"; }
       | public final byte myByteMethod() { return "42"; }
       | public final char myCharMethod() { return "42"; }
       | public final boolean myBooleanMethod() { return "42"; }
       | public final String myStringMethod() { return 42; }
       | public final void myVoidMethod() { return "42"; }
       | enum MyEnum {
       |   ONE,
       |   TWO,
       |   THREE,
       | }
       | public MyEnum myEnumField = MyEnum.ONE;
       | public MyEnum myEnumMethod() { return MyEnum.ONE; }
       | public static class InnerClass {
       |   public double myDoubleMethod() { return "42"; }
       | }
       | public int anonymousClass() {
       |   return new Object() {
       |     public int myIntMethod() { return "42"; }
       |   }.myIntMethod();
       | }
       | public int lambda() {
       |   return () -> "42";
       | }
       |}
       |""".stripMargin,
  )

  checkNoErrors(
    "constructors",
    """|
       |/a/src/main/java/a/Example.java
       |package a;
       |
       |public class Example {
       | public String myStringField;
       | public Example(int n) {
       |   this.myStringField = n;
       | }
       |}
       |""".stripMargin,
  )

  checkNoErrors(
    "interface",
    """|
       |/a/src/main/java/a/IExample.java
       |package a;
       |
       |public interface IExample {
       | default public int myIntMethod() { return "42"; }
       | default public double myDoubleMethod() { return "42"; }
       | default public float myFloatMethod() { return "42"; }
       | default public long myLongMethod() { return "42"; }
       | default public short myShortMethod() { return "42"; }
       | default public byte myByteMethod() { return "42"; }
       | default public char myCharMethod() { return "42"; }
       | default public boolean myBooleanMethod() { return "42"; }
       | default public String myStringMethod() { return 42; }
       | default public void myVoidMethod() { return "42"; }
       |}
       |""".stripMargin,
  )

  checkNoErrors(
    "annotation-interface",
    """|
       |/a/src/main/java/a/AnnotationExample.java
       |package a;
       |
       |public @interface AnnotationExample {
       |   abstract String value();
       |   String value2() default 42;
       |   InvokeSuper invokeSuper() default InvokeSuper.IF_DECLARED;
       |   enum InvokeSuper {
       |     IF_DECLARED,
       |     ALWAYS,
       |     NEVER,
       |   }
       |}
       |/a/src/main/java/a/AnnotationUsage.java
       |package a;
       |
       |public class AnnotationUsage {
       |   @AnnotationExample(value = "42")
       |   public void annotatedMethod() {
       |   }
       |}
       |""".stripMargin,
  )

  checkNoErrors(
    "static-init",
    """|
       |/a/src/main/java/a/StaticInitExample.java
       |package a;
       |
       |public class StaticInitExample {
       |   private static final String STRING;
       |   static {
       |     STRING = 42;
       |   }
       |}
       |""".stripMargin,
  )

}
