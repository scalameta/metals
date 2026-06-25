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
       |  public String greet(String name) {
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
       |  int count(String s) {
       |    throw new UnsupportedOperationException("Not yet implemented");
       |  }
       |
       |  @Override
       |  String value() {
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
       |  public void store(String value) {
       |    throw new UnsupportedOperationException("Not yet implemented");
       |  }
       |
       |  @Override
       |  public String unwrap() {
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
       |  public String greet(String name) {
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
       |  public String format(String pattern, Object... args) {
       |    throw new UnsupportedOperationException("Not yet implemented");
       |  }
       |}
       |""".stripMargin,
    fileName = "Main.java",
    filterAction = onlyImplementAll,
    retryAction = 3,
  )

  check(
    "adds-import",
    """|package a;
       |
       |interface Source {
       |  java.util.List<String> values();
       |}
       |
       |public class <<Main>> implements Source {
       |}
       |""".stripMargin,
    s"""|${ImplementAbstractMembers.title}
        |""".stripMargin,
    """|package a;
       |
       |import java.util.List;
       |
       |interface Source {
       |  java.util.List<String> values();
       |}
       |
       |public class Main implements Source {
       |  @Override
       |  public List<String> values() {
       |    throw new UnsupportedOperationException("Not yet implemented");
       |  }
       |}
       |""".stripMargin,
    fileName = "Main.java",
    filterAction = onlyImplementAll,
    retryAction = 3,
  )

  check(
    "existing-and-new-import",
    """|package a;
       |
       |import java.util.List;
       |
       |interface Repo {
       |  List<String> all();
       |  java.util.Map<String, Integer> index();
       |}
       |
       |public class <<Main>> implements Repo {
       |}
       |""".stripMargin,
    s"""|${ImplementAbstractMembers.title}
        |""".stripMargin,
    """|package a;
       |
       |import java.util.List;
       |import java.util.Map;
       |
       |interface Repo {
       |  List<String> all();
       |  java.util.Map<String, Integer> index();
       |}
       |
       |public class Main implements Repo {
       |  @Override
       |  public List<String> all() {
       |    throw new UnsupportedOperationException("Not yet implemented");
       |  }
       |
       |  @Override
       |  public Map<String, Integer> index() {
       |    throw new UnsupportedOperationException("Not yet implemented");
       |  }
       |}
       |""".stripMargin,
    fileName = "Main.java",
    filterAction = onlyImplementAll,
    retryAction = 3,
  )

  // A nested member type is shortened by importing its outermost enclosing
  // type and qualifying the rest with simple names.
  check(
    "nested-type",
    """|package a;
       |
       |interface Source {
       |  java.util.Map.Entry<String, String> entry();
       |}
       |
       |public class <<Main>> implements Source {
       |}
       |""".stripMargin,
    s"""|${ImplementAbstractMembers.title}
        |""".stripMargin,
    """|package a;
       |
       |import java.util.Map;
       |
       |interface Source {
       |  java.util.Map.Entry<String, String> entry();
       |}
       |
       |public class Main implements Source {
       |  @Override
       |  public Map.Entry<String, String> entry() {
       |    throw new UnsupportedOperationException("Not yet implemented");
       |  }
       |}
       |""".stripMargin,
    fileName = "Main.java",
    filterAction = onlyImplementAll,
    retryAction = 3,
  )

  // The file declares a type named `List`, so the inherited `java.util.List`
  // must stay fully qualified to avoid a clash.
  check(
    "clash-keeps-fqn",
    """|package a;
       |
       |interface List {
       |  java.util.List<String> values();
       |}
       |
       |public class <<Main>> implements List {
       |}
       |""".stripMargin,
    s"""|${ImplementAbstractMembers.title}
        |""".stripMargin,
    """|package a;
       |
       |interface List {
       |  java.util.List<String> values();
       |}
       |
       |public class Main implements List {
       |  @Override
       |  public java.util.List<String> values() {
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
