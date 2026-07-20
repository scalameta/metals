package pc

import tests.JavaModuleExports
import tests.pc.BaseJavaCompletionSuite

/**
 * Completions for classes that live in a JDK module which is not exported to
 * the unnamed module by default (here `jdk.compiler`). Referencing these
 * classes only compiles because the presentation compiler passes the required
 * `--add-exports` flags. These tests make sure that once the module class is
 * resolved we still only surface members that are actually accessible.
 */
class CompletionModuleSuite extends BaseJavaCompletionSuite {

  override protected def javacOptions: List[String] = JavaModuleExports.options

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
