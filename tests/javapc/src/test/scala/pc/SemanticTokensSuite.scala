package pc

import tests.pc.BaseJavaSemanticTokensSuite

class SemanticTokensSuite extends BaseJavaSemanticTokensSuite {

  check(
    "field",
    """
      |class A {
      |    public static java.util.List<String> field = java.util.Arrays.asList("blah");
      |}
      |""".stripMargin,
    """|package field;
       |        ^^^^^ namespace
       |
       |class A {
       |      ^ class
       |    public static java.util.List<String> field = java.util.Arrays.asList("blah");
       |                  ^^^^ namespace
       |                       ^^^^ namespace
       |                            ^^^^ class
       |                                 ^^^^^^ class
       |                                         ^^^^^ property
       |                                                 ^^^^ namespace
       |                                                      ^^^^ namespace
       |                                                           ^^^^^^ class
       |                                                                  ^^^^^^ method
       |}
       |""".stripMargin,
  )

  check(
    "method",
    """
      |class A {
      |    public static int method(String param) {
      |        var local = params + "local";
      |        return local.length();
      |    }
      |}
      |""".stripMargin,
    """|package method;
       |        ^^^^^^ namespace
       |
       |class A {
       |      ^ class
       |    public static int method(String param) {
       |                      ^^^^^^ method
       |                             ^^^^^^ class
       |                                    ^^^^^ parameter
       |        var local = params + "local";
       |            ^^^^^ parameter
       |                    ^^^^^^ class
       |        return local.length();
       |               ^^^^^ parameter
       |                     ^^^^^^ class
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "anon-method",
    """
      |class A {
      |    public static void method() {
      |        new java.io.Closeable() {
      |            private int field;
      |            public void close() {
      |            }
      |        };
      |    }
      |}
      |""".stripMargin,
    """|package anonmethod;
       |        ^^^^^^^^^^ namespace
       |
       |class A {
       |      ^ class
       |    public static void method() {
       |                       ^^^^^^ method
       |        new java.io.Closeable() {
       |            ^^^^ namespace
       |                 ^^ namespace
       |                    ^^^^^^^^^ class
       |            private int field;
       |                        ^^^^^ property
       |            public void close() {
       |                        ^^^^^ method
       |            }
       |        };
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "tabs",
    """
      |import java.util.List;
      |class A {
      |\t\tpublic static List<String> field = java.util.Arrays.asList("blah");
      |}
      |""".stripMargin.replace("\\t", "\t"),
    """|package tabs;
       |        ^^^^ namespace
       |
       |import java.util.List;
       |       ^^^^ namespace
       |            ^^^^ namespace
       |                 ^^^^ class
       |class A {
       |      ^ class
       |  public static List<String> field = java.util.Arrays.asList("blah");
       |                ^^^^ class
       |                     ^^^^^^ class
       |                             ^^^^^ property
       |                                     ^^^^ namespace
       |                                          ^^^^ namespace
       |                                               ^^^^^^ class
       |                                                      ^^^^^^ method
       |}
       |""".stripMargin,
  )
}
