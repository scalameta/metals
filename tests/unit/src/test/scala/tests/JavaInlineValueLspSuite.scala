package tests

import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.metals.MetalsEnrichments._

import org.eclipse.{lsp4j => l}

class JavaInlineValueLspSuite extends BaseLspSuite("java-inline-value") {

  private def cleanMarkedLayout(layout: String): String =
    layout
      .replace("<<start>>", "")
      .replace("<<end>>", "")
      .replace("[[start]]", "")
      .replace("[[end]]", "")

  private def markedRanges(
      file: String,
      markedLayout: String,
  ): (l.Range, l.Range) = {
    val fileMarker = s"\n/$file\n"
    val fileStart = markedLayout.indexOf(fileMarker)
    assert(fileStart >= 0, s"missing file $file in:\n$markedLayout")
    val contentStart = fileStart + fileMarker.length
    val cleanLayout = cleanMarkedLayout(markedLayout)
    val cleanFileText = cleanLayout.substring(contentStart)
    val input = Input.VirtualFile(file, cleanFileText)

    def rangeFor(start: String, end: String, fallback: Boolean): l.Range = {
      val startMarker = markedLayout.indexOf(start)
      val endMarker = markedLayout.indexOf(end)
      if (startMarker < 0 || endMarker < 0) {
        assert(
          fallback,
          s"missing $start/$end marker in:\n$markedLayout",
        )
        rangeFor("<<start>>", "<<end>>", fallback = false)
      } else {
        assert(
          startMarker < endMarker,
          s"invalid marker order for $start/$end in:\n$markedLayout",
        )
        val startOffset =
          cleanMarkedLayout(markedLayout.substring(contentStart, startMarker))
            .length()
        val endOffset =
          cleanMarkedLayout(markedLayout.substring(contentStart, endMarker))
            .length()
        Position.Range(input, startOffset, endOffset).toLsp
      }
    }

    val range = rangeFor("<<start>>", "<<end>>", fallback = false)
    val stoppedLocation = rangeFor("[[start]]", "[[end]]", fallback = true)
    (range, stoppedLocation)
  }

  test("capability") {
    cleanWorkspace()
    for {
      result <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {}
           |}
           |""".stripMargin
      )
    } yield assert(result.getCapabilities().getInlineValueProvider().isRight())
  }

  test("variable-lookup") {
    val file = "a/src/main/java/a/Main.java"
    val layout =
      """|
         |/metals.json
         |{
         |  "a": {}
         |}
         |/a/src/main/java/a/Main.java
         |package a;
         |
         |public class Main {
         |  void run(String name, int count) {
         |    <<start>>int total = count + 1;
         |    System.out.println(name + total);<<end>>
         |  }
         |}
         |""".stripMargin
    val (range, _) = markedRanges(file, layout)
    for {
      _ <- initialize(cleanMarkedLayout(layout))
      _ <- server.didOpen(file)
      rendered <- server.inlineValuesText(file, range, range)
      values <- server.inlineValues(file, range, range)
    } yield {
      assertNoDiff(
        rendered,
        """package a;
          |
          |public class Main {
          |  void run(String name, int count) {
          |    int total<<total>> = count<<count>> + 1;
          |    System.out.println(name<<name>> + total<<total>>);
          |  }
          |}
          |""".stripMargin,
      )
      assert(rendered.contains("<<total>>"))
      assert(rendered.contains("<<count>>"))
      assert(rendered.contains("<<name>>"))
      assert(
        values.forall(_.getInlineValueVariableLookup().isCaseSensitiveLookup())
      )
    }
  }

  test("stopped-location") {
    val file = "a/src/main/java/a/Main.java"
    val layout =
      """|
         |/metals.json
         |{
         |  "a": {}
         |}
         |/a/src/main/java/a/Main.java
         |package a;
         |
         |public class Main {
         |  void run(String name, int count) {
         |    <<start>>[[start]]int total = count + 1;[[end]]
         |    System.out.println(name + total);<<end>>
         |  }
         |}
         |""".stripMargin
    val (range, stoppedLocation) = markedRanges(file, layout)
    for {
      _ <- initialize(cleanMarkedLayout(layout))
      _ <- server.didOpen(file)
      rendered <- server.inlineValuesText(file, range, stoppedLocation)
    } yield {
      assertNoDiff(
        rendered,
        """package a;
          |
          |public class Main {
          |  void run(String name, int count) {
          |    int total<<total>> = count<<count>> + 1;
          |    System.out.println(name + total);
          |  }
          |}
          |""".stripMargin,
      )
    }
  }

  test("member-select") {
    val file = "a/src/main/java/a/Main.java"
    val layout =
      """|
         |/metals.json
         |{
         |  "a": {}
         |}
         |/a/src/main/java/a/Main.java
         |package a;
         |
         |public class Main {
         |  static class Person { String name; Person parent; }
         |  void run(Person person) {
         |    <<start>>System.out.println(person.name + person.parent.name);<<end>>
         |  }
         |}
         |""".stripMargin
    val (range, _) = markedRanges(file, layout)
    for {
      _ <- initialize(cleanMarkedLayout(layout))
      _ <- server.didOpen(file)
      rendered <- server.inlineValuesText(file, range, range)
    } yield {
      assertNoDiff(
        rendered,
        """package a;
          |
          |public class Main {
          |  static class Person { String name; Person parent; }
          |  void run(Person person) {
          |    System.out.println(person.name<<person.name>> + person.parent.name<<person.parent.name>>);
          |  }
          |}
          |""".stripMargin,
      )
    }
  }

  test("array-access") {
    val file = "a/src/main/java/a/Main.java"
    val layout =
      """|
         |/metals.json
         |{
         |  "a": {}
         |}
         |/a/src/main/java/a/Main.java
         |package a;
         |
         |public class Main {
         |  void run(int[] items, int i) {
         |    <<start>>int value = items[i];
         |    System.out.println(value);<<end>>
         |  }
         |}
         |""".stripMargin
    val (range, _) = markedRanges(file, layout)
    for {
      _ <- initialize(cleanMarkedLayout(layout))
      _ <- server.didOpen(file)
      rendered <- server.inlineValuesText(file, range, range)
    } yield {
      assertNoDiff(
        rendered,
        """package a;
          |
          |public class Main {
          |  void run(int[] items, int i) {
          |    int value<<value>> = items[i<<i>>]<<items[i]>>;
          |    System.out.println(value<<value>>);
          |  }
          |}
          |""".stripMargin,
      )
    }
  }

  test("this-member") {
    val file = "a/src/main/java/a/Main.java"
    val layout =
      """|
         |/metals.json
         |{
         |  "a": {}
         |}
         |/a/src/main/java/a/Main.java
         |package a;
         |
         |public class Main {
         |  String name;
         |  void run() {
         |    <<start>>System.out.println(this.name);<<end>>
         |  }
         |}
         |""".stripMargin
    val (range, _) = markedRanges(file, layout)
    for {
      _ <- initialize(cleanMarkedLayout(layout))
      _ <- server.didOpen(file)
      rendered <- server.inlineValuesText(file, range, range)
    } yield {
      assertNoDiff(
        rendered,
        """package a;
          |
          |public class Main {
          |  String name;
          |  void run() {
          |    System.out.println(this.name<<this.name>>);
          |  }
          |}
          |""".stripMargin,
      )
    }
  }

  test("getter-call") {
    val file = "a/src/main/java/a/Main.java"
    val layout =
      """|
         |/metals.json
         |{
         |  "a": {}
         |}
         |/a/src/main/java/a/Main.java
         |package a;
         |
         |import java.util.List;
         |
         |public class Main {
         |  static class User { String getName() { return ""; } }
         |  void run(User user, List<String> items) {
         |    <<start>>System.out.println(user.getName() + items.size() + items.toString());<<end>>
         |  }
         |}
         |""".stripMargin
    val (range, _) = markedRanges(file, layout)
    for {
      _ <- initialize(cleanMarkedLayout(layout))
      _ <- server.didOpen(file)
      rendered <- server.inlineValuesText(file, range, range)
    } yield {
      assertNoDiff(
        rendered,
        """package a;
          |
          |import java.util.List;
          |
          |public class Main {
          |  static class User { String getName() { return ""; } }
          |  void run(User user, List<String> items) {
          |    System.out.println(user.getName() + items.size() + items.toString());
          |  }
          |}
          |""".stripMargin,
      )
    }
  }

  test("instanceof-pattern") {
    val file = "a/src/main/java/a/Main.java"
    val layout =
      """|
         |/metals.json
         |{
         |  "a": {}
         |}
         |/a/src/main/java/a/Main.java
         |package a;
         |
         |public class Main {
         |  void run(Object obj) {
         |    <<start>>if (obj instanceof String s) {
         |      System.out.println(s);
         |    }<<end>>
         |  }
         |}
         |""".stripMargin
    val (range, _) = markedRanges(file, layout)
    for {
      _ <- initialize(cleanMarkedLayout(layout))
      _ <- server.didOpen(file)
      rendered <- server.inlineValuesText(file, range, range)
    } yield {
      assertNoDiff(
        rendered,
        """package a;
          |
          |public class Main {
          |  void run(Object obj) {
          |    if (obj<<obj>> instanceof String s<<s>>) {
          |      System.out.println(s<<s>>);
          |    }
          |  }
          |}
          |""".stripMargin,
      )
    }
  }

  test("field-access") {
    val file = "a/src/main/java/a/Main.java"
    val layout =
      """|
         |/metals.json
         |{
         |  "a": {}
         |}
         |/a/src/main/java/a/Main.java
         |package a;
         |
         |public class Main {
         |  String name = "World";
         |  static final String GREETING = "Hello";
         |  void run() {
         |    <<start>>String hello = name;
         |    String label = GREETING + " " + name;<<end>>
         |  }
         |}
         |""".stripMargin
    val (range, _) = markedRanges(file, layout)
    for {
      _ <- initialize(cleanMarkedLayout(layout))
      _ <- server.didOpen(file)
      rendered <- server.inlineValuesText(file, range, range)
    } yield {
      assertNoDiff(
        rendered,
        """package a;
          |
          |public class Main {
          |  String name = "World";
          |  static final String GREETING = "Hello";
          |  void run() {
          |    String hello<<hello>> = name<<this.name>>;
          |    String label<<label>> = GREETING<<a.Main.GREETING>> + " " + name<<this.name>>;
          |  }
          |}
          |""".stripMargin,
      )
    }
  }

  test("explicit-static-import") {
    val file = "a/src/main/java/a/Main.java"
    val layout =
      """|
         |/metals.json
         |{
         |  "a": {}
         |}
         |/a/src/main/java/a/Main.java
         |package a;
         |
         |import static java.lang.Math.PI;
         |
         |public class Main {
         |  void run() {
         |    <<start>>double radius = PI;
         |    double PI = 1.0;
         |    System.out.println(PI);<<end>>
         |  }
         |}
         |""".stripMargin
    val (range, _) = markedRanges(file, layout)
    for {
      _ <- initialize(cleanMarkedLayout(layout))
      _ <- server.didOpen(file)
      rendered <- server.inlineValuesText(file, range, range)
    } yield {
      assertNoDiff(
        rendered,
        """package a;
          |
          |import static java.lang.Math.PI;
          |
          |public class Main {
          |  void run() {
          |    double radius<<radius>> = PI<<java.lang.Math.PI>>;
          |    double PI<<PI>> = 1.0;
          |    System.out.println(PI<<PI>>);
          |  }
          |}
          |""".stripMargin,
      )
    }
  }

  test("inherited-field") {
    val file = "a/src/main/java/a/Main.java"
    val layout =
      """|
         |/metals.json
         |{
         |  "a": {}
         |}
         |/a/src/main/java/a/Base.java
         |package a;
         |
         |class Base {
         |  int inherited = 42;
         |}
         |/a/src/main/java/a/Main.java
         |package a;
         |
         |public class Main extends Base {
         |  void run() {
         |    <<start>>System.out.println(inherited);<<end>>
         |  }
         |}
         |""".stripMargin
    val (range, _) = markedRanges(file, layout)
    for {
      _ <- initialize(cleanMarkedLayout(layout))
      _ <- server.didOpen(file)
      rendered <- server.inlineValuesText(file, range, range)
    } yield {
      assertNoDiff(
        rendered,
        """package a;
          |
          |public class Main extends Base {
          |  void run() {
          |    System.out.println(inherited<<inherited>>);
          |  }
          |}
          |""".stripMargin,
      )
    }
  }

  test("field-local-and-static-classification") {
    val file = "a/src/main/java/a/A.java"
    val layout =
      """|
         |/metals.json
         |{
         |  "a": {}
         |}
         |/a/src/main/java/a/A.java
         |package a;
         |
         |public class A {
         |  int field;
         |  static int staticField;
         |
         |  void instanceMethod(int param) {
         |    <<start>>int local = 1;
         |    field = local + param;
         |  }
         |
         |  static void staticMethod() {
         |    staticField++;
         |  }<<end>>
         |}
         |""".stripMargin
    val (range, _) = markedRanges(file, layout)
    for {
      _ <- initialize(cleanMarkedLayout(layout))
      _ <- server.didOpen(file)
      rendered <- server.inlineValuesText(file, range, range)
    } yield {
      assertNoDiff(
        rendered,
        """package a;
          |
          |public class A {
          |  int field;
          |  static int staticField;
          |
          |  void instanceMethod(int param) {
          |    int local<<local>> = 1;
          |    field<<this.field>> = local<<local>> + param<<param>>;
          |  }
          |
          |  static void staticMethod() {
          |    staticField<<a.A.staticField>>++;
          |  }
          |}
          |""".stripMargin,
      )
    }
  }

  test("local-shadows-field") {
    val file = "a/src/main/java/a/A.java"
    val layout =
      """|
         |/metals.json
         |{
         |  "a": {}
         |}
         |/a/src/main/java/a/A.java
         |package a;
         |
         |public class A {
         |  int value;
         |
         |  void foo() {
         |    <<start>>int value = 1;
         |    System.out.println(value);<<end>>
         |  }
         |}
         |""".stripMargin
    val (range, _) = markedRanges(file, layout)
    for {
      _ <- initialize(cleanMarkedLayout(layout))
      _ <- server.didOpen(file)
      rendered <- server.inlineValuesText(file, range, range)
    } yield {
      assertNoDiff(
        rendered,
        """package a;
          |
          |public class A {
          |  int value;
          |
          |  void foo() {
          |    int value<<value>> = 1;
          |    System.out.println(value<<value>>);
          |  }
          |}
          |""".stripMargin,
      )
    }
  }

  test("block-local-does-not-shadow-field-after-scope") {
    val file = "a/src/main/java/a/A.java"
    val layout =
      """|
         |/metals.json
         |{
         |  "a": {}
         |}
         |/a/src/main/java/a/A.java
         |package a;
         |
         |public class A {
         |  int value;
         |
         |  void foo() {
         |    <<start>>{
         |      int value = 1;
         |    }
         |    System.out.println(value);<<end>>
         |  }
         |}
         |""".stripMargin
    val (range, _) = markedRanges(file, layout)
    for {
      _ <- initialize(cleanMarkedLayout(layout))
      _ <- server.didOpen(file)
      rendered <- server.inlineValuesText(file, range, range)
    } yield {
      assertNoDiff(
        rendered,
        """package a;
          |
          |public class A {
          |  int value;
          |
          |  void foo() {
          |    {
          |      int value<<value>> = 1;
          |    }
          |    System.out.println(value<<this.value>>);
          |  }
          |}
          |""".stripMargin,
      )
    }
  }

  test("declaration-name-before-initializer") {
    val file = "a/src/main/java/a/A.java"
    val layout =
      """|
         |/metals.json
         |{
         |  "a": {}
         |}
         |/a/src/main/java/a/A.java
         |package a;
         |
         |public class A {
         |  static class Other { String name; }
         |  void foo(Other other) {
         |    <<start>>String name = other.name;<<end>>
         |  }
         |}
         |""".stripMargin
    val (range, _) = markedRanges(file, layout)
    for {
      _ <- initialize(cleanMarkedLayout(layout))
      _ <- server.didOpen(file)
      rendered <- server.inlineValuesText(file, range, range)
    } yield {
      assertNoDiff(
        rendered,
        """package a;
          |
          |public class A {
          |  static class Other { String name; }
          |  void foo(Other other) {
          |    String name<<name>> = other.name<<other.name>>;
          |  }
          |}
          |""".stripMargin,
      )
    }
  }

  test("array-access-safe-index-only") {
    val file = "a/src/main/java/a/A.java"
    val layout =
      """|
         |/metals.json
         |{
         |  "a": {}
         |}
         |/a/src/main/java/a/A.java
         |package a;
         |
         |public class A {
         |  int getIndex() { return 0; }
         |  void foo(int[] arr, int i) {
         |    <<start>>int a = arr[i];
         |    int b = arr[i++];
         |    int c = arr[getIndex()];<<end>>
         |  }
         |}
         |""".stripMargin
    val (range, _) = markedRanges(file, layout)
    for {
      _ <- initialize(cleanMarkedLayout(layout))
      _ <- server.didOpen(file)
      rendered <- server.inlineValuesText(file, range, range)
    } yield {
      assertNoDiff(
        rendered,
        """package a;
          |
          |public class A {
          |  int getIndex() { return 0; }
          |  void foo(int[] arr, int i) {
          |    int a<<a>> = arr[i<<i>>]<<arr[i]>>;
          |    int b<<b>> = arr[i<<i>>++];
          |    int c<<c>> = arr[getIndex()];
          |  }
          |}
          |""".stripMargin,
      )
    }
  }

  test("lambda-local-does-not-shadow-field-after-scope") {
    val file = "a/src/main/java/a/A.java"
    val layout =
      """|
         |/metals.json
         |{
         |  "a": {}
         |}
         |/a/src/main/java/a/A.java
         |package a;
         |
         |import java.util.List;
         |
         |public class A {
         |  String item;
         |
         |  void foo(List<String> items) {
         |    <<start>>items.forEach(item -> {
         |      System.out.println(item);
         |    });
         |
         |    System.out.println(item);<<end>>
         |  }
         |}
         |""".stripMargin
    val (range, _) = markedRanges(file, layout)
    for {
      _ <- initialize(cleanMarkedLayout(layout))
      _ <- server.didOpen(file)
      rendered <- server.inlineValuesText(file, range, range)
    } yield {
      assertNoDiff(
        rendered,
        """package a;
          |
          |import java.util.List;
          |
          |public class A {
          |  String item;
          |
          |  void foo(List<String> items) {
          |    items.forEach(item<<item>> -> {
          |      System.out.println(item<<item>>);
          |    });
          |
          |    System.out.println(item<<this.item>>);
          |  }
          |}
          |""".stripMargin,
      )
    }
  }

  test("local-class-field-does-not-leak-into-method-scope") {
    val file = "a/src/main/java/a/A.java"
    val layout =
      """|
         |/metals.json
         |{
         |  "a": {}
         |}
         |/a/src/main/java/a/A.java
         |package a;
         |
         |public class A {
         |  int value;
         |
         |  void foo() {
         |    <<start>>class Local {
         |      int value;
         |    }
         |
         |    System.out.println(value);<<end>>
         |  }
         |}
         |""".stripMargin
    val (range, _) = markedRanges(file, layout)
    for {
      _ <- initialize(cleanMarkedLayout(layout))
      _ <- server.didOpen(file)
      rendered <- server.inlineValuesText(file, range, range)
    } yield {
      assertNoDiff(
        rendered,
        """package a;
          |
          |public class A {
          |  int value;
          |
          |  void foo() {
          |    class Local {
          |      int value;
          |    }
          |
          |    System.out.println(value<<this.value>>);
          |  }
          |}
          |""".stripMargin,
      )
    }
  }

  test("local-class-field-access") {
    val file = "a/src/main/java/a/A.java"
    val layout =
      """|
         |/metals.json
         |{
         |  "a": {}
         |}
         |/a/src/main/java/a/A.java
         |package a;
         |
         |public class A {
         |  void foo() {
         |    class Local {
         |      int value;
         |
         |      void bar() {
         |        <<start>>System.out.println(value);<<end>>
         |      }
         |    }
         |  }
         |}
         |""".stripMargin
    val (range, _) = markedRanges(file, layout)
    for {
      _ <- initialize(cleanMarkedLayout(layout))
      _ <- server.didOpen(file)
      rendered <- server.inlineValuesText(file, range, range)
    } yield {
      assertNoDiff(
        rendered,
        """package a;
          |
          |public class A {
          |  void foo() {
          |    class Local {
          |      int value;
          |
          |      void bar() {
          |        System.out.println(value<<this.value>>);
          |      }
          |    }
          |  }
          |}
          |""".stripMargin,
      )
    }
  }

  test("for-loop-local-does-not-shadow-field-after-scope") {
    val file = "a/src/main/java/a/A.java"
    val layout =
      """|
         |/metals.json
         |{
         |  "a": {}
         |}
         |/a/src/main/java/a/A.java
         |package a;
         |
         |public class A {
         |  int i;
         |
         |  void foo() {
         |    <<start>>for (int i = 0; i < 10; i++) {
         |      System.out.println(i);
         |    }
         |
         |    System.out.println(i);<<end>>
         |  }
         |}
         |""".stripMargin
    val (range, _) = markedRanges(file, layout)
    for {
      _ <- initialize(cleanMarkedLayout(layout))
      _ <- server.didOpen(file)
      rendered <- server.inlineValuesText(file, range, range)
    } yield {
      assertNoDiff(
        rendered,
        """package a;
          |
          |public class A {
          |  int i;
          |
          |  void foo() {
          |    for (int i<<i>> = 0; i<<i>> < 10; i<<i>>++) {
          |      System.out.println(i<<i>>);
          |    }
          |
          |    System.out.println(i<<this.i>>);
          |  }
          |}
          |""".stripMargin,
      )
    }
  }

  test("enhanced-for-local-does-not-shadow-field-after-scope") {
    val file = "a/src/main/java/a/A.java"
    val layout =
      """|
         |/metals.json
         |{
         |  "a": {}
         |}
         |/a/src/main/java/a/A.java
         |package a;
         |
         |import java.util.List;
         |
         |public class A {
         |  String item;
         |
         |  void foo(List<String> items) {
         |    <<start>>for (String item : items) {
         |      System.out.println(item);
         |    }
         |
         |    System.out.println(item);<<end>>
         |  }
         |}
         |""".stripMargin
    val (range, _) = markedRanges(file, layout)
    for {
      _ <- initialize(cleanMarkedLayout(layout))
      _ <- server.didOpen(file)
      rendered <- server.inlineValuesText(file, range, range)
    } yield {
      assertNoDiff(
        rendered,
        """package a;
          |
          |import java.util.List;
          |
          |public class A {
          |  String item;
          |
          |  void foo(List<String> items) {
          |    for (String item<<item>> : items<<items>>) {
          |      System.out.println(item<<item>>);
          |    }
          |
          |    System.out.println(item<<this.item>>);
          |  }
          |}
          |""".stripMargin,
      )
    }
  }

  test("catch-parameter-does-not-shadow-field-after-scope") {
    val file = "a/src/main/java/a/A.java"
    val layout =
      """|
         |/metals.json
         |{
         |  "a": {}
         |}
         |/a/src/main/java/a/A.java
         |package a;
         |
         |public class A {
         |  Exception e;
         |
         |  void foo() {
         |    <<start>>try {
         |      throw new RuntimeException();
         |    } catch (Exception e) {
         |      System.out.println(e);
         |    }
         |
         |    System.out.println(e);<<end>>
         |  }
         |}
         |""".stripMargin
    val (range, _) = markedRanges(file, layout)
    for {
      _ <- initialize(cleanMarkedLayout(layout))
      _ <- server.didOpen(file)
      rendered <- server.inlineValuesText(file, range, range)
    } yield {
      assertNoDiff(
        rendered,
        """package a;
          |
          |public class A {
          |  Exception e;
          |
          |  void foo() {
          |    try {
          |      throw new RuntimeException();
          |    } catch (Exception e<<e>>) {
          |      System.out.println(e<<e>>);
          |    }
          |
          |    System.out.println(e<<this.e>>);
          |  }
          |}
          |""".stripMargin,
      )
    }
  }

  test("static-initializer-does-not-emit-this-field") {
    val file = "a/src/main/java/a/A.java"
    val layout =
      """|
         |/metals.json
         |{
         |  "a": {}
         |}
         |/a/src/main/java/a/A.java
         |package a;
         |
         |public class A {
         |  int x;
         |  static int y;
         |
         |  static {
         |    <<start>>System.out.println(y);
         |    System.out.println(x);<<end>>
         |  }
         |}
         |""".stripMargin
    val (range, _) = markedRanges(file, layout)
    for {
      _ <- initialize(cleanMarkedLayout(layout))
      _ <- server.didOpen(file)
      rendered <- server.inlineValuesText(file, range, range)
    } yield {
      assertNoDiff(
        rendered,
        """package a;
          |
          |public class A {
          |  int x;
          |  static int y;
          |
          |  static {
          |    System.out.println(y<<a.A.y>>);
          |    System.out.println(x<<this.x>>);
          |  }
          |}
          |""".stripMargin,
      )
    }
  }

  test("static-field-initializer") {
    val file = "a/src/main/java/a/A.java"
    val layout =
      """|
         |/metals.json
         |{
         |  "a": {}
         |}
         |/a/src/main/java/a/A.java
         |package a;
         |
         |public class A {
         |  int x;
         |  static int y;
         |  <<start>>static int z = y;<<end>>
         |}
         |""".stripMargin
    val (range, _) = markedRanges(file, layout)
    for {
      _ <- initialize(cleanMarkedLayout(layout))
      _ <- server.didOpen(file)
      rendered <- server.inlineValuesText(file, range, range)
    } yield {
      assertNoDiff(
        rendered,
        """package a;
          |
          |public class A {
          |  int x;
          |  static int y;
          |  static int z<<a.A.z>> = y<<a.A.y>>;
          |}
          |""".stripMargin,
      )
    }
  }

  test("instance-field-initializer") {
    val file = "a/src/main/java/a/A.java"
    val layout =
      """|
         |/metals.json
         |{
         |  "a": {}
         |}
         |/a/src/main/java/a/A.java
         |package a;
         |
         |public class A {
         |  int x;
         |  <<start>>int y = x;<<end>>
         |}
         |""".stripMargin
    val (range, _) = markedRanges(file, layout)
    for {
      _ <- initialize(cleanMarkedLayout(layout))
      _ <- server.didOpen(file)
      rendered <- server.inlineValuesText(file, range, range)
    } yield {
      assertNoDiff(
        rendered,
        """package a;
          |
          |public class A {
          |  int x;
          |  int y<<this.y>> = x<<this.x>>;
          |}
          |""".stripMargin,
      )
    }
  }

  test("variable-name-matching-type-name") {
    val file = "a/src/main/java/a/A.java"
    val layout =
      """|
         |/metals.json
         |{
         |  "a": {}
         |}
         |/a/src/main/java/a/A.java
         |package a;
         |
         |public class A {
         |  static class foo {}
         |  void run() {
         |    <<start>>foo foo = new foo();
         |    System.out.println(foo);<<end>>
         |  }
         |}
         |""".stripMargin
    val (range, _) = markedRanges(file, layout)
    for {
      _ <- initialize(cleanMarkedLayout(layout))
      _ <- server.didOpen(file)
      rendered <- server.inlineValuesText(file, range, range)
    } yield {
      assertNoDiff(
        rendered,
        """package a;
          |
          |public class A {
          |  static class foo {}
          |  void run() {
          |    foo foo<<foo>> = new foo();
          |    System.out.println(foo<<foo>>);
          |  }
          |}
          |""".stripMargin,
      )
    }
  }

  test("member-select-with-call-receiver-is-not-emitted") {
    val file = "a/src/main/java/a/A.java"
    val layout =
      """|
         |/metals.json
         |{
         |  "a": {}
         |}
         |/a/src/main/java/a/A.java
         |package a;
         |
         |public class A {
         |  static class User { String name; }
         |  User getUser() { return new User(); }
         |
         |  void foo(User user) {
         |    <<start>>System.out.println(user.name);
         |    System.out.println(getUser().name);<<end>>
         |  }
         |}
         |""".stripMargin
    val (range, _) = markedRanges(file, layout)
    for {
      _ <- initialize(cleanMarkedLayout(layout))
      _ <- server.didOpen(file)
      rendered <- server.inlineValuesText(file, range, range)
    } yield {
      assertNoDiff(
        rendered,
        """package a;
          |
          |public class A {
          |  static class User { String name; }
          |  User getUser() { return new User(); }
          |
          |  void foo(User user) {
          |    System.out.println(user.name<<user.name>>);
          |    System.out.println(getUser().name);
          |  }
          |}
          |""".stripMargin,
      )
    }
  }
}
