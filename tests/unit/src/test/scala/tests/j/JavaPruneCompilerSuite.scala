package tests.j

class JavaPruneCompilerSuite extends BaseJavaPruneCompilerSuite {

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
}
