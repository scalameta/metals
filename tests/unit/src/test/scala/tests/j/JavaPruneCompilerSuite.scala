package tests.j

import tests.JavaModuleExports

class JavaPruneCompilerSuite extends BaseJavaPruneCompilerSuite {

  // Dropping super(value) introduces a dependency attribution error, which
  // stops javac flow analysis before it reports the caller's missing return.
  checkErrors(
    "constructor-delegation-caller-missing-return",
    """|/example/Base.java
       |package example;
       |public class Base {
       |  public Base(String value) {}
       |}
       |/example/Derived.java
       |package example;
       |public class Derived extends Base {
       |  public Derived(String value) { super(value); }
       |}
       |/example/Caller.java
       |package example;
       |public class Caller {
       |  public String use() { new Derived("value"); }
       |}
       |""".stripMargin,
    "example/Caller.java",
    """|example/Caller.java:3:47: error: missing return statement
       |  public String use() { new Derived("value"); }
       |                                              ^
       |""".stripMargin,
  )

  checkNoErrors(
    "class",
    """|/a/src/main/java/example/foo/Example.java
       |package example.foo;
       |import example.bar.*;
       |public class Example {
       |  public static void main(String[] args) {
       |    System.out.println(Greeting.GREETING);
       |    System.out.println(Greeting2.GREETING2);
       |  }
       |}
       |/b/src/main/java/example/bar/Greeting.java
       |package example.bar;
       |public class Greeting {
       |  public static final String GREETING = "Hello, World!";
       |}
       |/c/Greeting2.java
       |package example.bar;
       |import example.broken.Broken;
       |public class Greeting2 {
       |  public static final String GREETING2 = "Hello, World!";
       |  public static final String NUMBER_GREETING = Broken.number;
       |}
       |/d/src/main/java/example/broken/Broken.java
       |package example.broken;
       |public class Broken {
       |  public static final int number = "42";
       |}
       |""".stripMargin,
    "a/src/main/java/example/foo/Example.java",
  )

  checkNoErrors(
    "warnings",
    """|/foo/Example.java
       |package foo;
       |public class Example implements java.io.Serializable {
       |}
       |""".stripMargin,
    "foo/Example.java",
  )

  checkNoErrors(
    "jdk-add-exports",
    """|/foo/Example.java
       |import com.sun.tools.javac.code.Symbol;
       |public class Example {
       |    public static final Symbol symbol = null;
       |}
       |""".stripMargin,
    "foo/Example.java",
    JavaModuleExports.options,
  )

  checkNoErrors(
    "import-same-nested-package",
    """|/a/b/Example1.java
       |package a.b;
       |public class Example1 {
       |    public static final String greeting = "hello";
       |}
       |/a/b/Example2.java
       |package a.b;
       |public class Example2 {
       |    public static final String example1 = Example1.greeting;
       |}
       |""".stripMargin,
    "a/b/Example2.java",
  )

  checkErrors(
    "type-error",
    """|
       |/a/Example.java
       |package a;
       |
       |public class Example {
       | public static int foo() {
       |    int blah = "42";
       |    return blah;
       |  }
       |}
       |""".stripMargin,
    "a/Example.java",
    """|a/Example.java:5:16: error: incompatible types: java.lang.String cannot be converted to int
       |    int blah = "42";
       |               ^^^^
       |""".stripMargin,
  )

  checkNoErrors(
    "empty-file",
    """|/foo/Example.java
       |package foo; /*
       |public class Example implements java.io.Serializable {
       |} */
       |/foo/Main.java
       |package foo;
       |public class Main {
       |    public static void main(String[] args) {
       |        System.out.println("Hello, World!");
       |    }
       |}
       |""".stripMargin,
    "foo/Main.java",
  )
}
