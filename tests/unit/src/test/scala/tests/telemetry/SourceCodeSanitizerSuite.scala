package tests.telemetry

import tests.BaseSuite
import scala.meta.internal.metals.SourceCodeSanitizer
import scala.meta.internal.metals.ScalametaSourceCodeTransformer

class SourceCodeSanitizerSuite extends BaseSuite {

  val sanitizer = new SourceCodeSanitizer(ScalametaSourceCodeTransformer)

  val sampleScalaInput =
    """
      |package some.namespace.of.my.app
      |class Foo{
      |  def myFoo: Int = 42
      |}
      |trait Bar{
      | def myBarSecret: String = "my_super-secret-code"
      |}
      |object FooBar extends Foo with Bar{
      |  def compute(input: String, other: Bar): Unit = 
      |    if(myBarSecret.contains("super-secret-code") || this.myBarSecret == other.myBarSecret) myFoo * 42
      |    else -1
      |}
    """.stripMargin
  val sampleScalaOutput =
    """package som0.namxxxxx1.of.my.ap4
      |class Fo0 { def myFx5: Int = 42 }
      |trait Ba1 { def myBxxxxxxx6: String = "--_-----------------" }
      |object Fooxx7 extends Fo0 with Ba1 { def comxxx8(inpx9: String, oth10: Ba1): Unit = if (myBxxxxxxx6.contains("-----------------") || this.myBxxxxxxx6 == oth10.myBxxxxxxx6) myFx5 * 42 else -1 }
    """.stripMargin

  val sampleStackTraceElements =
    """
      |scala.meta.internal.pc.completions.OverrideCompletions.scala$meta$internal$pc$completions$OverrideCompletions$$getMembers(OverrideCompletions.scala:180)
      | scala.meta.internal.pc.completions.OverrideCompletions$OverrideCompletion.contribute(OverrideCompletions.scala:79)
      | scala.meta.internal.pc.CompletionProvider.expected$1(CompletionProvider.scala:439)
      | scala.meta.internal.pc.CompletionProvider.safeCompletionsAt(CompletionProvider.scala:499)
      | scala.meta.internal.pc.CompletionProvider.completions(CompletionProvider.scala:58)
      | scala.meta.internal.pc.ScalaPresentationCompiler.$anonfun$complete$1(ScalaPresentationCompiler.scala:169)
      |
      |""".stripMargin

  val sampleJavaInput =
    """
      |package scala.meta.internal.telemetry;
      |
      |public class ServiceEndpoint<I, O> {
      |	final private String uri;
      |	final private String method;
      |	final private Class<I> inputType;
      |	final private Class<O> outputType;
      |
      |	public ServiceEndpoint(String method, String uri, Class<I> inputType, Class<O> outputType) {
      |		this.uri = uri;
      |		this.method = method;
      |		this.inputType = inputType;
      |		this.outputType = outputType;
      |	}
      |
      |	public String getUri() {
      |		return uri;
      |	}
      |
      |	public String getMethod() {
      |		return method;
	}
  """.stripMargin

  val sampleStackTrace =
    """
      |java.lang.RuntimeException
      |    at java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method)
      |    at java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:77)
      |    at java.base/jdk.internal.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:43)
      |    at java.base/java.lang.reflect.Method.invoke(Method.java:568)
      |    at dotty.tools.repl.Rendering.$anonfun$4(Rendering.scala:110)
      |    at scala.Option.flatMap(Option.scala:283)
      |    at dotty.tools.repl.Rendering.valueOf(Rendering.scala:110)
      |    at dotty.tools.repl.Rendering.renderVal(Rendering.scala:152)
      |    at dotty.tools.repl.ReplDriver.$anonfun$7(ReplDriver.scala:388)
      |    at scala.runtime.function.JProcedure1.apply(JProcedure1.java:15)
    """.stripMargin

  test("erases names from sources in Scala") {
    val input = sampleScalaInput
    val expected = sampleScalaOutput
    assertEquals(expected.trim(), sanitizer(input).trim())
  }

  test("erases sources in non parsable sources") { // TODO: Java parsing
    val input = sampleJavaInput
    assertEquals("<unparsable>", sanitizer(input).trim())
  }

  test("erases names from markdown snippets") {
    val input =
      s"""
         |## Source code:
         |```
         |$sampleScalaInput
         |```
         |
         |## Scala source code 
         |```scala
         |$sampleScalaInput
         |```
         |
         |## Java source code
         |```
         |${sampleJavaInput}
         |```
         |
         |## Stacktrace:
         |```
         |$sampleStackTrace
         |```
         |
         |## Stack trace elements
         |```scala
         |$sampleStackTraceElements
         |```
         |
    """.stripMargin

    val expected =
      s"""
         |## Source code:
         |```
         |$sampleScalaOutput
         |```
         |
         |## Scala source code 
         |```scala
         |$sampleScalaOutput
         |```
         |
         |## Java source code
         |```
         |<unparsable>
         |```
         |
         |## Stacktrace:
         |```
         |$sampleStackTrace
         |```
         |
         |## Stack trace elements
         |```scala
         |$sampleStackTraceElements
         |```
         |
    """.stripMargin
    def trimLines(string: String) = string.linesIterator
      .map(_.trim())
      .filterNot(_.isEmpty())
      .mkString(System.lineSeparator())
    assertEquals(trimLines(expected), trimLines(sanitizer(input)))
  }

}
