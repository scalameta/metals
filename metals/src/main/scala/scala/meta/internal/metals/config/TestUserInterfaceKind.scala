package scala.meta.internal.metals.config

sealed trait TestUserInterfaceKind
object TestUserInterfaceKind {
  object CodeLenses extends TestUserInterfaceKind {
    override def toString: String = "code lenses"
  }
  object TestExplorer extends TestUserInterfaceKind {
    override def toString: String = "test explorer"
  }
}
