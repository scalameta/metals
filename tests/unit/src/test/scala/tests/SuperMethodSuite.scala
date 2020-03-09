package tests

import scala.meta.internal.implementation.GoToSuperMethod

class SuperMethodSuite extends BaseSuite {

  test("super method symbol formatting") {
    eq("akka/actor/Actor#preStart().", "akka.actor.Actor#preStart")
  }

  def eq(current: String, converted: String): Unit = {
    assertNoDiff(GoToSuperMethod.formatSymbolForQuickPick(current), converted)
  }
}
