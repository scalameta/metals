import scala.meta.internal.sbtmetals.BuildInfo

val checkSemanticdb =
  taskKey[Unit]("Checks that semanticdb is correctly configured")

// scala 2
lazy val a = project
  .in(file("a"))
  .settings(
    scalaVersion := "2.13.5",
    inConfig(Compile) {
      checkSemanticdb := {
        assertSemanticdbForScala2.value
        compile.value
      }
    },
    inConfig(Test) { checkSemanticdb := assertSemanticdbForScala2.value },
  )

// scala 3
lazy val b = project
  .in(file("b"))
  .settings(
    scalaVersion := "3.0.1",
    inConfig(Compile) { checkSemanticdb := assertSemanticdbForScala3.value },
    inConfig(Test) { checkSemanticdb := assertSemanticdbForScala3.value },
  )

// scala 2.12
lazy val c = project
  .in(file("c"))
  .settings(
    scalaVersion := "2.12.17",
    inConfig(Compile) {
      checkSemanticdb := {
        assertSemanticdbForScala2.value
        compile.value
      }
    },
    inConfig(Test) { checkSemanticdb := assertSemanticdbForScala2.value },
  )

// bsp disabled
lazy val d = project
  .in(file("d"))
  .settings(
    bspEnabled := false,
    inConfig(Compile) { checkSemanticdb := assertSemanticdbDisabled.value },
    inConfig(Test) { checkSemanticdb := assertSemanticdbDisabled.value },
  )

ThisBuild / checkSemanticdb := {
  (a / Compile / checkSemanticdb).value
  (a / Test / checkSemanticdb).value

  (b / Compile / checkSemanticdb).value
  (b / Test / checkSemanticdb).value

  (c / Compile / checkSemanticdb).value
  (c / Test / checkSemanticdb).value

  (d / Compile / checkSemanticdb).value
  (d / Test / checkSemanticdb).value
}

def assertSemanticdbForScala2 = Def.task {
  val enabled = semanticdbEnabled.value
  val sOptions = scalacOptions.value
  val targetRoot = semanticdbTargetRoot.value
  val sourceRoot = (ThisBuild / baseDirectory).value
  val sv = scalaVersion.value
  val project = thisProject.value
  val config = configuration.value
  val jOptions = javacOptions.value

  assert(enabled, s"semanticdb is disabled in ${project.id}/${config.id}")
  assertPlugin(sOptions, s"semanticdb-scalac", sv, BuildInfo.semanticdbVersion)
  assertOption(sOptions, "-Yrangepos")
  assertOption(sOptions, "-P:semanticdb:synthetics:on")
  assertOption(sOptions, "-P:semanticdb:failures:warning")
  assertOptionValue(sOptions, "-P:semanticdb:sourceroot", sourceRoot.toString)
  assertOptionValue(sOptions, "-P:semanticdb:targetroot", targetRoot.toString)

  assert(
    jOptions.exists(_.startsWith("-Xplugin:semanticdb")),
    "no javac-semanticdb plugin",
  )
}

def assertSemanticdbForScala3 = Def.taskDyn {
  CrossVersion.partialVersion((ThisBuild / sbtVersion).value) match {
    case Some((1, 4)) => Def.task(()) // scala 3 support was added in sbt 1.5
    case _ =>
      Def.task {
        val enabled = semanticdbEnabled.value
        val sOptions = scalacOptions.value
        val targetRoot = semanticdbTargetRoot.value
        val project = thisProject.value
        val config = configuration.value

        assert(enabled, s"semanticdb is disabled in ${project.id}/${config.id}")
        assertOption(sOptions, "-Xsemanticdb")
        assertOptionValue(sOptions, s"-semanticdb-target", targetRoot.toString)
      }
  }
}

def assertSemanticdbDisabled = Def.task {
  val enabled = semanticdbEnabled.value
  val project = thisProject.value
  val config = configuration.value
  assert(!enabled, s"semanticdb is enabled in ${project.id}/${config.id}")
}

def assertPlugin(
    scalacOptions: Seq[String],
    pluginName: String,
    scalaVersion: String,
    pluginVersion: String,
): Unit = {
  val options = scalacOptions.filter(opt =>
    opt.startsWith("-Xplugin") && opt.contains(pluginName)
  )
  options.size match {
    case 0 => throw new Exception(s"missing compiler plugin $pluginName")
    case 1 =>
      assert(
        options.head.endsWith(
          s"${pluginName}_$scalaVersion-$pluginVersion.jar"
        ),
        s"incorrect version of $pluginName",
      )
    case _ =>
      throw new Exception(s"compiler plugin $pluginName found more than once")
  }
}

def assertOption(scalacOptions: Seq[String], expectedOption: String): Unit = {
  val options = scalacOptions.filter(_ == expectedOption)
  options.size match {
    case 0 => throw new Exception(s"missing $expectedOption")
    case 1 => ()
    case _ => throw new Exception(s"duplicated $expectedOption")
  }
}

def assertOptionValue(
    scalacOptions: Seq[String],
    expectedOption: String,
    expectedValue: String,
): Unit = {
  val options = scalacOptions.filter(_.startsWith(expectedOption))
  options.size match {
    case 0 =>
      throw new Exception(s"missing $expectedOption")
    case 1 =>
      val option = options.head
      val value =
        if (expectedOption == option) scalacOptions.dropWhile(_ != option)(1)
        else option.stripPrefix(expectedOption).stripPrefix(":")
      assert(
        value == expectedValue,
        s"wrong value for $expectedOption (actual: $value, expected: $expectedValue)",
      )
    case _ =>
      throw new Exception(s"duplicated $expectedOption")
  }
}
