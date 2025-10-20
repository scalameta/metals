package pc

import java.nio.charset.StandardCharsets

import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.JdkSources

import tests.pc.BaseJavaSemanticdbSuite

class SemanticdbSuite extends BaseJavaSemanticdbSuite {

  check(
    "basic",
    """
      |class A {
      |    public int foo() {
      |        int x = 1;
      |        x = x + 1;
      |        return x;
      |    }
      |}
      """.stripMargin,
    """|   package basic;
       |//         ^^^^^ reference basic/
       |
       |   class A {
       |//       ^ definition basic/A#
       |//       ^ definition basic/A#`<init>`().
       |       public int foo() {
       |//                ^^^ definition basic/A#foo().
       |           int x = 1;
       |//             ^ definition local0
       |           x = x + 1;
       |//         ^ reference local0
       |//             ^ reference local0
       |           return x;
       |//                ^ reference local0
       |       }
       |   }
       |""".stripMargin,
  )

  test("jdk") {
    val Right(jdkSources) = JdkSources()
    var isFound = false
    FileIO.withJarFileSystem(jdkSources, create = false, close = true) { root =>
      FileIO.listAllFilesRecursively(root).foreach { file =>
        if (file.toNIO.toString.endsWith("/java/lang/String.java")) {
          isFound = true
          val text = FileIO.slurp(file, StandardCharsets.UTF_8)
          val doc = textDocument(text, file.toNIO.toUri)
          assert(clue(doc).getOccurrencesCount() > 1_000)
        }
      }
    }
    assert(isFound, "java/lang/String.java not found")
  }

}
