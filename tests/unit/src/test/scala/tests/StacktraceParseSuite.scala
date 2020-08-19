package tests

import scala.meta.internal.metals.StacktraceAnalyzer

class StacktraceParseSuite extends BaseSuite {

  test("convert-stacktrace-line-to-symbol") {

    // --- CLASS ---
    testConversion("p1.p2.Class.method", "p1/p2/Class#")
    testConversion(
      "p1.p2.OutsideClass$InsideClass.method",
      List("p1/p2/OutsideClass#", "p1/p2/OutsideClass#InsideClass#")
    )

    // --- TRAIT ---
    // For trait both lines are present with the same line
    testConversion("p1.p2.Trait.method$", "p1/p2/Trait#")
    testConversion("p1.p2.Trait.method", "p1/p2/Trait#")
    // <package> -> <package> -> <trait> -> <trait> -> <method>
    testConversion(
      "p1.p2.OutsideTrait$InsideTrait.method$",
      List("p1/p2/OutsideTrait#", "p1/p2/OutsideTrait#InsideTrait#")
    )
    testConversion(
      "p1.p2.OutsideTrait$InsideTrait.method",
      List("p1/p2/OutsideTrait#", "p1/p2/OutsideTrait#InsideTrait#")
    )

    // --- OBJECT ---
    testConversion("p1.p2.Object$.method", "p1/p2/Object.")
    testConversion(
      "p1.p2.ObjectParent$ObjectException$.method",
      List("p1/p2/ObjectParent.", "p1/p2/ObjectParent.ObjectException.")
    )

    // exception in template body
    testConversion("p1.p2.ConstructorClass.<init>", "p1/p2/ConstructorClass#")
    testConversion(
      "p1.p2.ConstructorObject$.<init>",
      "p1/p2/ConstructorObject."
    )

    // exception inside anonymous block
    testConversion("p1.p2.Object$.$anonfun$method$1", "p1/p2/Object.")
    testConversion("p1.p2.Class.$anonfun$method$1", "p1/p2/Class#")

    // specialized method
    testConversion("p1.p2.Object$.foreach$mVc$sp", "p1/p2/Object.")
    testConversion("p1.p2.Class.foreach$mVc$sp", "p1/p2/Class#")
  }

  def testConversion(line: String, expected: String): Unit = {
    val obtained = StacktraceAnalyzer.convert(line)
    assert(clue(obtained).contains(clue(expected)))
  }

  def testConversion(line: String, expectedOneOf: List[String]): Unit = {
    val obtained = StacktraceAnalyzer.convert(line)
    assert(clue(expectedOneOf).diff(clue(obtained)).nonEmpty)
  }
}
