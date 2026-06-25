package tests.codeactions

import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.codeactions.ImplementAbstractMembers

class JavaImplementAbstractMembersLspSuite
    extends BaseCodeActionLspSuite("java-implement-abstract-members") {

  override def userConfig: UserConfiguration =
    super.userConfig.copy(presentationCompilerDiagnostics = true)

  override protected def toPath(
      fileName: String,
      isSource: Boolean = true,
  ): String =
    if (isSource) s"a/src/main/java/a/$fileName"
    else s"a/$fileName"

  private val onlyImplementAll: org.eclipse.lsp4j.CodeAction => Boolean =
    _.getTitle() == ImplementAbstractMembers.title

  check(
    "interface",
    """|package a;
       |
       |interface Greeter {
       |  String greet(String name);
       |}
       |
       |public class <<Main>> implements Greeter {
       |}
       |""".stripMargin,
    s"""|${ImplementAbstractMembers.title}
        |""".stripMargin,
    """|package a;
       |
       |interface Greeter {
       |  String greet(String name);
       |}
       |
       |public class Main implements Greeter {
       |  @Override
       |  public java.lang.String greet(java.lang.String name) {
       |    throw new UnsupportedOperationException("Not yet implemented");
       |  }
       |}
       |""".stripMargin,
    fileName = "Main.java",
    filterAction = onlyImplementAll,
    retryAction = 3,
  )

  check(
    "abstract-class",
    """|package a;
       |
       |abstract class Base {
       |  abstract int count(String s);
       |  abstract String value();
       |  void concrete() {}
       |}
       |
       |public class <<Main>> extends Base {
       |}
       |""".stripMargin,
    s"""|${ImplementAbstractMembers.title}
        |""".stripMargin,
    """|package a;
       |
       |abstract class Base {
       |  abstract int count(String s);
       |  abstract String value();
       |  void concrete() {}
       |}
       |
       |public class Main extends Base {
       |  @Override
       |  int count(java.lang.String s) {
       |    throw new UnsupportedOperationException("Not yet implemented");
       |  }
       |
       |  @Override
       |  java.lang.String value() {
       |    throw new UnsupportedOperationException("Not yet implemented");
       |  }
       |}
       |""".stripMargin,
    fileName = "Main.java",
    filterAction = onlyImplementAll,
    retryAction = 3,
  )

  check(
    "generics",
    """|package a;
       |
       |interface Box<T> {
       |  T unwrap();
       |  void store(T value);
       |}
       |
       |public class <<Main>> implements Box<String> {
       |}
       |""".stripMargin,
    s"""|${ImplementAbstractMembers.title}
        |""".stripMargin,
    """|package a;
       |
       |interface Box<T> {
       |  T unwrap();
       |  void store(T value);
       |}
       |
       |public class Main implements Box<String> {
       |  @Override
       |  public void store(java.lang.String value) {
       |    throw new UnsupportedOperationException("Not yet implemented");
       |  }
       |
       |  @Override
       |  public java.lang.String unwrap() {
       |    throw new UnsupportedOperationException("Not yet implemented");
       |  }
       |}
       |""".stripMargin,
    fileName = "Main.java",
    filterAction = onlyImplementAll,
    retryAction = 3,
  )

  check(
    "with-existing-members",
    """|package a;
       |
       |interface Greeter {
       |  String greet(String name);
       |}
       |
       |public class <<Main>> implements Greeter {
       |  private final String prefix = "Hello";
       |}
       |""".stripMargin,
    s"""|${ImplementAbstractMembers.title}
        |""".stripMargin,
    """|package a;
       |
       |interface Greeter {
       |  String greet(String name);
       |}
       |
       |public class Main implements Greeter {
       |  private final String prefix = "Hello";
       |
       |  @Override
       |  public java.lang.String greet(java.lang.String name) {
       |    throw new UnsupportedOperationException("Not yet implemented");
       |  }
       |}
       |""".stripMargin,
    fileName = "Main.java",
    filterAction = onlyImplementAll,
    retryAction = 3,
  )

  check(
    "varargs",
    """|package a;
       |
       |interface Formatter {
       |  String format(String pattern, Object... args);
       |}
       |
       |public class <<Main>> implements Formatter {
       |}
       |""".stripMargin,
    s"""|${ImplementAbstractMembers.title}
        |""".stripMargin,
    """|package a;
       |
       |interface Formatter {
       |  String format(String pattern, Object... args);
       |}
       |
       |public class Main implements Formatter {
       |  @Override
       |  public java.lang.String format(java.lang.String pattern, java.lang.Object... args) {
       |    throw new UnsupportedOperationException("Not yet implemented");
       |  }
       |}
       |""".stripMargin,
    fileName = "Main.java",
    filterAction = onlyImplementAll,
    retryAction = 3,
  )

  checkNoAction(
    "already-implemented",
    """|package a;
       |
       |interface Greeter {
       |  String greet(String name);
       |}
       |
       |public class <<Main>> implements Greeter {
       |  public String greet(String name) {
       |    return name;
       |  }
       |}
       |""".stripMargin,
    fileName = "Main.java",
    filterAction = onlyImplementAll,
  )
}
