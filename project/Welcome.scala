import sbtwelcome._

object Welcome {

  val logo =
    """|             _        _     
       |  /\/\   ___| |_ __ _| |___ 
       | /    \ / _ \ __/ _` | / __|
       |/ /\/\ \  __/ || (_| | \__ \
       |\/    \/\___|\__\__,_|_|___/
       |
       |""".stripMargin

  val tasks = Seq(
    UsefulTask(
      "unit/testOnly tests.DefinitionSuite",
      "run a specific unit test suite.",
    ),
    UsefulTask(
      "unit/testOnly tests.DefinitionSuite -- *exact-test-name*",
      "run a specific test case inside the unit test suite.",
    ),
    UsefulTask(
      "unit/testOnly tests.DefinitionSuite -- -F",
      "use `-F` flag to show the full stack trace in case it is missing",
    ),
    UsefulTask(
      "slow/testOnly -- tests.sbt.*",
      "run all slow tests inside tests.sbt package, they will publish needed mtags locally.",
    ),
    UsefulTask(
      "~cross/testOnly tests.hover.HoverTermSuite",
      "automatically rerun on changes a specific presentation compiler test, great for edit/test/debug workflows.",
    ),
    UsefulTask(
      "+cross/test",
      "run all presentation compiler tests for all non-deprecated Scala versions.",
    ),
    UsefulTask(
      "publishLocal",
      "publish Metals for the currently used Scala version.",
    ),
    UsefulTask(
      "+publishLocal",
      "publish Metals for all supported used Scala versions.",
    ),
    UsefulTask(
      "quick-publish-local",
      "publish Metals artifacts but with only limited set of Scala versions",
    ),
    UsefulTask(
      s"++${V.lastPublishedScala3} mtags/publishLocal",
      "publish changes for a single Scala version, especially useful if working on a feature inside mtags module." +
        " `publishLocal` will still need to be run before at least once.",
    ),
  )

}
