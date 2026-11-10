package inlayHints

import tests.pc.BaseJavaInlayHintsSuite

final class JavaInlayHintsSuite extends BaseJavaInlayHintsSuite {

  check(
    "var-inferred-type",
    """|import java.util.List;
       |public class Example {
       |  void run(List<String> names) {
       |    var first/*: String<<java/lang/String#>>*/ = names.get(0);
       |    var size/*: int*/ = names.size();
       |  }
       |}
       |""".stripMargin,
    namedParameters = false,
  )

  check(
    "var-obvious-type-skipped",
    """|public class Example {
       |  void run() {
       |    var a = new StringBuilder();
       |    var b = "text";
       |    var c = (Object) a;
       |    var d = new int[] {1};
       |  }
       |}
       |""".stripMargin,
    namedParameters = false,
  )

  check(
    "explicit-type-skipped",
    """|public class Example {
       |  void run() {
       |    String s = String.valueOf(1);
       |  }
       |}
       |""".stripMargin,
    namedParameters = false,
  )

  check(
    "var-disabled",
    """|public class Example {
       |  void run() {
       |    var n = Integer.valueOf(1);
       |  }
       |}
       |""".stripMargin,
    inferredTypes = false,
    namedParameters = false,
  )

  check(
    "parameter-names",
    """|public class Example {
       |  static int clamp(int value, int lower, int upper) { return value; }
       |  int run() {
       |    return clamp(/*value: */1, /*lower: */2, /*upper: */3);
       |  }
       |}
       |""".stripMargin,
    inferredTypes = false,
  )

  check(
    "parameter-name-matches-argument",
    """|public class Example {
       |  static int clamp(int value, int lower, int upper) { return value; }
       |  int run(int value) {
       |    int lower = 0;
       |    return clamp(value, lower, /*upper: */10);
       |  }
       |}
       |""".stripMargin,
    inferredTypes = false,
  )

  check(
    "constructor-parameter-names",
    """|public class Example {
       |  Example(String label, boolean enabled) {}
       |  void run() {
       |    new Example(/*label: */"a", /*enabled: */true);
       |  }
       |}
       |""".stripMargin,
    inferredTypes = false,
  )

  check(
    "parameter-names-disabled",
    """|public class Example {
       |  static void two(int first, int second) {}
       |  void run() { two(1, 2); }
       |}
       |""".stripMargin,
    inferredTypes = false,
    namedParameters = false,
  )

  check(
    "varargs",
    """|public class Example {
       |  static void log(String message, Object... values) {}
       |  void run() {
       |    log(/*message: */"a", /*...values: */1, 2, 3);
       |  }
       |}
       |""".stripMargin,
    inferredTypes = false,
  )

  check(
    "anonymous-class-constructor",
    """|public class Example {
       |  static class Base { Base(String label, int size) {} }
       |  void run() {
       |    new Base(/*label: */"a", /*size: */1) {};
       |  }
       |}
       |""".stripMargin,
    inferredTypes = false,
  )

  check(
    "varargs-array-argument",
    """|public class Example {
       |  static void log(String message, Object... values) {}
       |  void run(Object[] items) {
       |    log(/*message: */"a", /*values: */items);
       |  }
       |}
       |""".stripMargin,
    inferredTypes = false,
  )

  check(
    "this-and-super-calls",
    """|public class Example {
       |  static class Base { Base(int size) {} }
       |  static class Child extends Base {
       |    Child(int size, String label) { super(/*size: */size + 1); }
       |    Child() { this(/*size: */1, /*label: */"a"); }
       |  }
       |}
       |""".stripMargin,
    inferredTypes = false,
  )

  check(
    "generic-and-nested-types",
    """|import java.util.List;
       |import java.util.Map;
       |public class Example {
       |  void run(Map<String, List<Integer>> map) {
       |    var entries/*: Set<<java/util/Set#>><Map.Entry<<java/util/Map#Entry#>><String<<java/lang/String#>>, List<<java/util/List#>><Integer<<java/lang/Integer#>>>>>*/ = map.entrySet();
       |    var stream/*: Stream<<java/util/stream/Stream#>><String<<java/lang/String#>>>*/ = map.keySet().stream();
       |  }
       |}
       |""".stripMargin,
    namedParameters = false,
  )

  check(
    "unresolved-type-skipped",
    """|public class Example {
       |  void run() {
       |    var x = Missing.create();
       |  }
       |}
       |""".stripMargin,
    namedParameters = false,
  )

  check(
    "tab-indentation",
    "public class Example {\n\tstatic void two(int first, int second) {}\n\tvoid run() {\n\t\ttwo(/*first: */1, /*second: */2);\n\t\tvar n/*: int*/ = 1 + 2;\n\t}\n}\n",
  )
}
