package tests

import scala.meta.internal.metals.StacktraceAnalyzer

class StacktraceParseSuite extends BaseSuite {

  // --- CLASS ---
  testConversion("p1.p2.Class.method", "p1/p2/Class#")
  testConversion(
    "p1.p2.OutsideClass$InsideClass.method",
    "p1/p2/OutsideClass#"
  )

  // --- TRAIT ---
  // For trait both lines are present with the same line
  testConversion("p1.p2.Trait.method$", "p1/p2/Trait#")
  testConversion("p1.p2.Trait.method", "p1/p2/Trait#")
  // <package> -> <package> -> <trait> -> <trait> -> <method>
  testConversion(
    "p1.p2.OutsideTrait$InsideTrait.method$",
    "p1/p2/OutsideTrait#"
  )
  testConversion(
    "p1.p2.OutsideTrait$InsideTrait.method",
    "p1/p2/OutsideTrait#"
  )

  // --- OBJECT ---
  testConversion("p1.p2.Object$.method", "p1/p2/Object.")
  testConversion(
    "p1.p2.ObjectParent$ObjectException$.method",
    "p1/p2/ObjectParent."
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

  def testConversion(line: String, expected: String): Unit = {
    test(line) {
      val obtained = StacktraceAnalyzer.toToplevelSymbol(line)
      assert(clue(obtained).contains(clue(expected)))
    }
  }

}
