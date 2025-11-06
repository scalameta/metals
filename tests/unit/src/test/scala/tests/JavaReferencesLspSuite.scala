package tests

import scala.concurrent.Future

class JavaReferencesLspSuite extends BaseRangesSuite("java-support") {

  override def assertCheck(
      filename: String,
      edit: String,
      expected: Map[String, String],
      base: Map[String, String],
  ): Future[Unit] = {
    server.assertReferences(filename, edit, expected, base)
  }

  check(
    "basic-reference",
    """|/a/src/main/java/a/Main.java
       |class BrokenCode {
       |  public static void main(String[] args) {
       |    String <<message>> = "Hello, World!";
       |    System.out.println(<<@@message>>);
       |  }
       |}
       |""".stripMargin,
  )

  check(
    "broken-code",
    """|/a/src/main/java/a/Main.java
       |class BrokenCode {
       |  public static void main(String[] args) {
       |    int x = "42";
       |    String <<message>> = "Hello, World!";
       |    System.out.println(<<@@message>>);
       |  }
       |}
       |""".stripMargin,
  )

  check(
    "broken-code-multiple",
    """|
       |/a/src/main/java/a/Main.java
       |package a;
       |class Main {
       |  public static void main(String[] args) {
       |    Integer x = "42";
       |    BrokenCode brokenCode = new BrokenCode();
       |    System.out.println(brokenCode.<<message>>);
       |  }
       |}
       |/a/src/main/java/a/BrokenCode.java
       |package a;
       |
       |class BrokenCode {
       |  public String <<message>> = "Hello, World!";
       |  public static void main(String[] args) {
       |    BrokenCode brokenCode = new BrokenCode();
       |    System.out.println(brokenCode.<<message>>);
       |  }
       |}
       |/a/src/main/java/a/Other.java
       |package a;
       |
       |class Other {
       |  BrokenCode brokenCode = new BrokenCode();
       |  void messages() {
       |    System.out.println(brokenCode.<<mess@@age>>);
       |  }
       |}
       |""".stripMargin,
    // make the code compile at least once
    additionalEdits = () => {
      for {
        _ <- server.didChange("a/src/main/java/a/Main.java") { txt =>
          txt.replace("Integer x = \"42\";", "Integer x = 42;")
        }
        _ <- server.didSave("a/src/main/java/a/Main.java")

        _ <- server.didChange("a/src/main/java/a/Main.java") { txt =>
          txt.replace("Integer x = 42;", "Integer x = \"42\";")
        }
        _ <- server.didSave("a/src/main/java/a/Main.java")
      } yield ()
    },
  )
}
