package tests

import scala.meta.internal.implementation.GoToSuperMethod

class SuperMethodSuite extends BaseSuite {

  test("super method symbol formatting for quick-pick") {
    checkConvert("akka/actor/Actor#preStart().", "akka.actor.Actor#preStart")
    checkConvert(
      "java/lang/Throwable#getMessage().",
      "java.lang.Throwable#getMessage"
    )
    // overloaded method
    checkConvert("a/MultiMethods#check(+3).", "a.MultiMethods#check")
    checkConvert("a/MultiMethods#check(+1).", "a.MultiMethods#check")
    // field
    checkConvert("a/A#zmx.", "a.A#zmx")
    // class inside object
    checkConvert("a/Generic.T#fx().", "a.Generic.T#fx")
  }

  def checkConvert(current: String, converted: String): Unit = {
    assertNoDiff(
      GoToSuperMethod.formatMethodSymbolForQuickPick(current),
      converted
    )
  }
}
