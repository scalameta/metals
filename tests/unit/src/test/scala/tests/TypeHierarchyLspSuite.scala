package tests

import scala.meta.internal.metals.InitializationOptions

class TypeHierarchyLspSuite
    extends BaseLspSuite("type-hierarchy")
    with TypeHierarchySpec {
  override def withMbt: Boolean = false

  override protected def initializationOptions: Option[InitializationOptions] =
    Some(TestingServer.TestDefault)

}
