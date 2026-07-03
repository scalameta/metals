package pc

import tests.pc.BaseJavaCompletionSuite

/**
 * Completions for classes that live in a JDK module which is not exported to
 * the unnamed module by default (here `jdk.compiler`). Referencing these
 * classes only compiles because the presentation compiler passes the required
 * `--add-exports` flags. These tests make sure that once the module class is
 * resolved we still only surface members that are actually accessible.
 */
class CompletionModuleSuite extends BaseJavaCompletionSuite {

  override protected def javacOptions: List[String] =
    List(
      // javac internal packages
      "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.resources=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
      // com.sun.source public APIs (sometimes need explicit exports)
      "--add-exports=jdk.compiler/com.sun.source.doctree=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.source.tree=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.source.util=ALL-UNNAMED",
      // java.base internal utilities
      "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
      "--add-exports=java.base/jdk.internal.reflect=ALL-UNNAMED",
      "--add-exports=java.base/jdk.internal.loader=ALL-UNNAMED",
      "--add-exports=java.base/sun.security.util=ALL-UNNAMED",
      "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
      // javadoc tools
      "--add-exports=jdk.javadoc/jdk.javadoc.internal.tool=ALL-UNNAMED",
      "--add-exports=jdk.javadoc/com.sun.tools.javadoc.main=ALL-UNNAMED",
    )

  check(
    "module-member-select",
    """package example;
      |
      |import com.sun.tools.javac.util.Context;
      |
      |public class Example {
      |  public void test(Context context) {
      |    context.put@@
      |  }
      |}
      |""".stripMargin,
    """|put(com.sun.tools.javac.util.Context.Key<T> key, com.sun.tools.javac.util.Context.Factory<T> fac)
       |put(com.sun.tools.javac.util.Context.Key<T> key, T data)
       |put(java.lang.Class<T> clazz, T data)
       |put(java.lang.Class<T> clazz, com.sun.tools.javac.util.Context.Factory<T> fac)
       |""".stripMargin,
  )

  // `com.sun.tools.javac.util.Context` has a private field `ht` that must never
  // leak into completions. Only the accessible `hashCode()` from Object matches.
  check(
    "module-hidden-private-member",
    """package example;
      |
      |import com.sun.tools.javac.util.Context;
      |
      |public class Example {
      |  public void test(Context context) {
      |    context.h@@
      |  }
      |}
      |""".stripMargin,
    """|hashCode()
       |""".stripMargin,
  )

  check(
    "module-public-member",
    """package example;
      |
      |import com.sun.tools.javac.code.Symbol;
      |
      |public class Example {
      |  public void test(Symbol symbol) {
      |    symbol.comp@@
      |  }
      |}
      |""".stripMargin,
    """|completer
       |complete()
       |""".stripMargin,
  )
}
