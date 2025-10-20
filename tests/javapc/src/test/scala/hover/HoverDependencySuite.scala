package hover

import coursierapi.Dependency
import tests.pc.BaseJavaHoverSuite

class HoverDependencySuite extends BaseJavaHoverSuite {

  override val documentationHoverEnabled = true

  override def extraDependencies: Seq[Dependency] = {
    Seq(
      Dependency.of(
        "com.google.guava",
        "guava",
        "31.1-jre",
      )
    )
  }

  check(
    "dep",
    """
      |import com.google.common.collect.Range;
      |
      |class A {
      |    public static void main(String args[]){
      |        Range<Integer> r = Range.cl@@osed(1, 10);
      |    }
      |}
      |""".stripMargin,
    """|```java
       |public static com.google.common.collect.Range<C> closed(C lower, C upper)
       |```
       |Returns a range that contains all values greater than or equal to `lower` and less than
       |or equal to `upper`.
       |""".stripMargin,
  )
}
