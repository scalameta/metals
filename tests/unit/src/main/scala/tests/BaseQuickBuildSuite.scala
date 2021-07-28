package tests

abstract class BaseQuickBuildSuite(suiteName: String)
    extends BaseLspSuite(suiteName)
    with QuickBuildInitializer
