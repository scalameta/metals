package pc

import tests.pc.BaseJavaSemanticdbSuite

class BatchSemanticdbSuite extends BaseJavaSemanticdbSuite {

  checkBatch(
    "basic",
    Map(
      "A.java" -> """package a;
                    |public class A {
                    |    public String foo() {
                    |        return b.B.greeting();
                    |    }
                    |}
      """.stripMargin,
      "B.java" -> """
                    |package b;
                    |public class B {
                    |    public static String greeting() {
                    |        return "Hello, world!";
                    |    }
                    |}
      """.stripMargin,
    ),
    """|==========
       |file:///A.java
       |   package a;
       |//         ^ reference a/
       |   public class A {
       |//              ^ definition a/A#
       |//              ^ definition a/A#`<init>`().
       |       public String foo() {
       |//            ^^^^^^ reference java/lang/String#
       |//                   ^^^ definition a/A#foo().
       |           return b.B.greeting();
       |//                ^ reference b/
       |//                  ^ reference b/B#
       |//                    ^^^^^^^^ reference b/B#greeting().
       |       }
       |   }
       |      
       |==========
       |file:///B.java
       |
       |   package b;
       |//         ^ reference b/
       |   public class B {
       |//              ^ definition b/B#
       |//              ^ definition b/B#`<init>`().
       |       public static String greeting() {
       |//                   ^^^^^^ reference java/lang/String#
       |//                          ^^^^^^^^ definition b/B#greeting().
       |           return "Hello, world!";
       |       }
       |   }
       |""".stripMargin,
  )
}
