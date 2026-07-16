package pc

import tests.pc.BaseJavaExtractMethodSuite

class JavaExtractMethodSuite extends BaseJavaExtractMethodSuite {

  checkEdit(
    "simple-expr",
    """|class A {
       |  int method(int i) {
       |    return i + 1;
       |  }
       |
       |  @@void main() {
       |    int a = <<123 + method(4)>>;
       |  }
       |}
       |""".stripMargin,
    """|class A {
       |  int method(int i) {
       |    return i + 1;
       |  }
       |
       |  private int newMethod() {
       |    return 123 + method(4);
       |  }
       |
       |  void main() {
       |    int a = newMethod();
       |  }
       |}
       |""".stripMargin,
  )

  checkEdit(
    "no-param",
    """|class A {
       |  @@void foo() {
       |    int c = 1;
       |    <<int b = 2;
       |    System.out.println(b);>>
       |  }
       |}
       |""".stripMargin,
    """|class A {
       |  private void newMethod() {
       |    int b = 2;
       |    System.out.println(b);
       |  }
       |
       |  void foo() {
       |    int c = 1;
       |    newMethod();
       |  }
       |}
       |""".stripMargin,
  )

  checkEdit(
    "single-param",
    """|class A {
       |  @@void foo() {
       |    int c = 1;
       |    <<int b = 2;
       |    System.out.println(c);>>
       |  }
       |}
       |""".stripMargin,
    """|class A {
       |  private void newMethod(int c) {
       |    int b = 2;
       |    System.out.println(c);
       |  }
       |
       |  void foo() {
       |    int c = 1;
       |    newMethod(c);
       |  }
       |}
       |""".stripMargin,
  )

  checkEdit(
    "with-return",
    """|class A {
       |  @@void foo() {
       |    int c = 1;
       |    <<int b = 2;
       |    int x = b + 10;>>
       |    System.out.println(x);
       |  }
       |}
       |""".stripMargin,
    """|class A {
       |  private int newMethod() {
       |    int b = 2;
       |    int x = b + 10;
       |    return x;
       |  }
       |
       |  void foo() {
       |    int c = 1;
       |    int x = newMethod();
       |    System.out.println(x);
       |  }
       |}
       |""".stripMargin,
  )

  checkEdit(
    "auto-import",
    """|class A {
       |  @@void foo() {
       |    <<java.util.UUID.randomUUID();>>
       |  }
       |}
       |""".stripMargin,
    """|import java.util.UUID;
       |
       |class A {
       |  private UUID newMethod() {
       |    return java.util.UUID.randomUUID();
       |  }
       |
       |  void foo() {
       |    newMethod();
       |  }
       |}
       |""".stripMargin,
  )

  checkEdit(
    "types",
    """|import java.util.Optional;
       |
       |class A {
       |  @@void foo() {
       |    <<String[] args = new String[] {"hello"};
       |      Optional<String> value = Optional.of(args[0]);>>
       |      System.out.println(value.get());
       |  }
       |}
       |""".stripMargin,
    """|import java.util.Optional;
       |
       |class A {
       |  private Optional<String> newMethod() {
       |    String[] args = new String[] {"hello"};
       |    Optional<String> value = Optional.of(args[0]);
       |    return value;
       |  }
       |
       |  void foo() {
       |    Optional<String> value = newMethod();
       |      System.out.println(value.get());
       |  }
       |}
       |""".stripMargin,
  )

  checkError(
    "return-selected",
    """|import java.util.Optional;
       |
       |class A {
       |  @@void foo() {
       |      <<if (2 == 2) {
       |        return false;
       |      }>>
       |      System.out.println(value.get());
       |  }
       |}
       |""".stripMargin,
    "Cannot extract selection that contains return statements",
  )

  checkError(
    "super-selected",
    """|class Parent {
       |  void doSomething() {}
       |}
       |
       |class A extends Parent {
       |  @@void foo() {
       |      <<super.doSomething();>>
       |  }
       |}
       |""".stripMargin,
    "Cannot extract selection that contains super calls",
  )

  checkError(
    "multiple-variables-after-selection",
    """|class A {
       |  @@void foo() {
       |      <<int a = 1;
       |      int b = 2;>>
       |      System.out.println(a + b);
       |  }
       |}
       |""".stripMargin,
    "No return type can be inferred, multiple variables are used after the selection.",
  )

  checkError(
    "mutated-captured-variable-assignment",
    """|class A {
       |  @@void foo() {
       |    int x = 0;
       |    <<x = 5;>>
       |    System.out.println(x);
       |  }
       |}
       |""".stripMargin,
    "Cannot extract selection that modifies captured variable(s): x",
  )

  checkError(
    "mutated-captured-variable-increment",
    """|class A {
       |  @@void foo() {
       |    int x = 0;
       |    <<x++;>>
       |    System.out.println(x);
       |  }
       |}
       |""".stripMargin,
    "Cannot extract selection that modifies captured variable(s): x",
  )

  checkError(
    "mutated-captured-variable-compound",
    """|class A {
       |  @@void foo() {
       |    int x = 0;
       |    <<x += 5;>>
       |    System.out.println(x);
       |  }
       |}
       |""".stripMargin,
    "Cannot extract selection that modifies captured variable(s): x",
  )

  checkEdit(
    "multiple-void",
    """|
       |
       |class A {
       |  void requireNonNull(Object obj, String message) {
       |    if (obj == null) {
       |      throw new NullPointerException(message);
       |    }
       |  }
       |
       |  @@void foo() {
       |<<    requireNonNull(filter, "filter is null");
       |      requireNonNull(orderBy, "orderBy is null");
       |      requireNonNull(nullTreatment, "nullTreatment is null");
       |      requireNonNull(processingMode, "processingMode is null");
       |      requireNonNull(arguments, "arguments is null");>>
       |  }
       |}
       |""".stripMargin,
    """|
       |class A {
       |  void requireNonNull(Object obj, String message) {
       |    if (obj == null) {
       |      throw new NullPointerException(message);
       |    }
       |  }
       |
       |  private void newMethod() {
       |    requireNonNull(filter, "filter is null");
       |    requireNonNull(orderBy, "orderBy is null");
       |    requireNonNull(nullTreatment, "nullTreatment is null");
       |    requireNonNull(processingMode, "processingMode is null");
       |    requireNonNull(arguments, "arguments is null");
       |  }
       |
       |  void foo() {
       |    newMethod();
       |  }
       |}
       |""".stripMargin,
  )

  checkEdit(
    "static-method",
    """|class A {
       |  @@static void foo() {
       |    <<int a = 1;
       |    int b = a + 2;>>
       |    System.out.println(b);
       |  }
       |}
       |""".stripMargin,
    """|class A {
       |  private static int newMethod() {
       |    int a = 1;
       |    int b = a + 2;
       |    return b;
       |  }
       |
       |  static void foo() {
       |    int b = newMethod();
       |    System.out.println(b);
       |  }
       |}
       |""".stripMargin,
  )

  checkEdit(
    "whitespace-in-selection",
    """|class A {
       |  @@void foo() {
       |    <</**/
       |    int a = 1;
       |    int b = a + 2;
       |    >>/**/
       |    System.out.println(b);
       |  }
       |}
       |""".stripMargin,
    """|class A {
       |  private int newMethod() {
       |    int a = 1;
       |    int b = a + 2;
       |    return b;
       |  }
       |
       |  void foo() {
       |    /**/
       |    int b = newMethod();
       |    /**/
       |    System.out.println(b);
       |  }
       |}
       |""".stripMargin,
  )
  checkEdit(
    "extract-partial",
    """|class A {
       |  @@void foo() {
       |    boolean a = true;
       |    boolean b = false;
       |    boolean c = true;
       |    boolean d = false;
       |    boolean e = <<a && b && c &&>> d;
       |   
       |    System.out.println(e);
       |  }
       |}
       |""".stripMargin,
    """|class A {
       |  private boolean newMethod(boolean a, boolean b, boolean c) {
       |    return a && b && c;
       |  }
       |
       |  void foo() {
       |    boolean a = true;
       |    boolean b = false;
       |    boolean c = true;
       |    boolean d = false;
       |    boolean e = newMethod(a, b, c) && d;
       |   
       |    System.out.println(e);
       |  }
       |}
       |""".stripMargin,
  )

  checkEdit(
    "method-type-param",
    """|class A {
       |  <T> T identity(T value) {
       |    return value;
       |  }
       |
       |  @@<T> void foo(T input) {
       |    T result = <<identity(input)>>;
       |    System.out.println(result);
       |  }
       |}
       |""".stripMargin,
    """|class A {
       |  <T> T identity(T value) {
       |    return value;
       |  }
       |
       |  private <T> T newMethod(T input) {
       |    return identity(input);
       |  }
       |
       |  <T> void foo(T input) {
       |    T result = newMethod(input);
       |    System.out.println(result);
       |  }
       |}
       |""".stripMargin,
  )

  checkEdit(
    "method-type-param-bounded",
    """|import java.util.List;
       |
       |class A {
       |  @@<T extends Number> void foo(List<T> items) {
       |    T first = <<items.get(0)>>;
       |    System.out.println(first);
       |  }
       |}
       |""".stripMargin,
    """|import java.util.List;
       |
       |class A {
       |  private <T extends Number> T newMethod(List<T> items) {
       |    return items.get(0);
       |  }
       |
       |  <T extends Number> void foo(List<T> items) {
       |    T first = newMethod(items);
       |    System.out.println(first);
       |  }
       |}
       |""".stripMargin,
  )

  checkEdit(
    "single-void-expr-statement",
    """|class A {
       |  @@void foo() {
       |    <<System.out.println("hello");>>
       |  }
       |}
       |""".stripMargin,
    """|class A {
       |  private void newMethod() {
       |    System.out.println("hello");
       |  }
       |
       |  void foo() {
       |    newMethod();
       |  }
       |}
       |""".stripMargin,
  )
}
