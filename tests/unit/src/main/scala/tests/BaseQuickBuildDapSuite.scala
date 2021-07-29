package tests

abstract class BaseQuickBuildDapSuite(suiteName: String)
    extends BaseDapSuite(suiteName)
    with QuickBuildInitializer
    with QuickBuildLayout
