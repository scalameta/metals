package scala.meta.internal.metals.debug.server

import java.net.URLClassLoader

import scala.collection.mutable
import scala.concurrent.ExecutionContext

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.debug.server.testing.FingerprintInfo
import scala.meta.internal.metals.debug.server.testing.LoggingEventHandler
import scala.meta.internal.metals.debug.server.testing.TestInternals
import scala.meta.internal.metals.debug.server.testing.TestServer
import scala.meta.io.AbsolutePath

import bloop.config.Config
import ch.epfl.scala.bsp4j.ScalaTestSuites
import ch.epfl.scala.debugadapter.CancelableFuture
import ch.epfl.scala.debugadapter.DebuggeeListener
import sbt.testing.Framework
import sbt.testing.SuiteSelector
import sbt.testing.TaskDef
import sbt.testing.TestSelector

class TestSuiteDebugAdapter(
    root: AbsolutePath,
    testClasses: ScalaTestSuites,
    project: DebugeeProject,
    userJavaHome: Option[String],
    discoveredTests: Map[Config.TestFramework, List[Discovered]],
    isDebug: Boolean = true,
)(implicit ec: ExecutionContext)
    extends MetalsDebuggee(project, userJavaHome) {

  override def name: String = {
    val selectedTests = testClasses
      .getSuites()
      .asScala
      .map { suite =>
        val tests = suite.getTests.asScala.mkString("(", ",", ")")
        s"${suite.getClassName()}$tests"
      }
      .mkString("[", ", ", "]")
    s"${getClass.getSimpleName}($selectedTests)"
  }

  def newClassLoader(parent: Option[ClassLoader]): ClassLoader = {
    val classpathEntries = classPath.map(_.toUri.toURL).toArray
    new URLClassLoader(classpathEntries, parent.orNull)
  }

  def suites(frameworks: Seq[Framework]): Map[Framework, List[TaskDef]] = {
    val testFilter = TestInternals.parseFilters(
      testClasses.getSuites.asScala.map(_.getClassName()).toList
    )
    val (subclassPrints, annotatedPrints) =
      TestInternals.getFingerprints(frameworks)
    val tasks = mutable.ArrayBuffer.empty[(Framework, TaskDef)]
    val seen = mutable.Set.empty[String]
    discoveredTests
      .flatMap { case (testFramework, discovered) =>
        discovered.map((testFramework, _))
      }
      .foreach { case (_, discovered) =>
        TestInternals
          .matchingFingerprints(subclassPrints, annotatedPrints, discovered)
          .foreach { case FingerprintInfo(_, _, framework, fingerprint) =>
            if (seen.add(discovered.className)) {
              tasks += (framework -> new TaskDef(
                discovered.className,
                fingerprint,
                false,
                Array(new SuiteSelector),
              ))
            }
          }
      }
    val selectedTests = testClasses
      .getSuites()
      .asScala
      .map(entry => (entry.getClassName(), entry.getTests().asScala.toList))
      .toMap
    tasks.toSeq
      .filter { case (_, taskDef) =>
        val fqn = taskDef.fullyQualifiedName()
        testFilter(fqn)
      }
      .groupBy(_._1)
      .view
      .mapValues(_.map { case (_, taskDef) =>
        selectedTests.get(taskDef.fullyQualifiedName()).getOrElse(Nil) match {
          case Nil => taskDef
          case selectedTests =>
            new TaskDef(
              taskDef.fullyQualifiedName(),
              taskDef.fingerprint(),
              false,
              selectedTests.map(test => new TestSelector(test)).toList.toArray,
            )
        }
      }.toList)
      .toMap
  }

  override def run(listener: DebuggeeListener): CancelableFuture[Unit] = {
    val loader = newClassLoader(Some(TestInternals.filteredLoader))
    val frameworks = discoveredTests.flatMap { case (framework, _) =>
      TestInternals.loadFramework(loader, framework.names)
    }
    val handler = new LoggingEventHandler(listener)
    val jvmOptions = testClasses.getJvmOptions.asScala.toList
    val testClassesEnvVariables =
      testClasses.getEnvironmentVariables().asScala.toList
    val buildServerEnvVariables = project.environmentVariablesAsStrings.toList
    // `testClassesEnvVariables` come from the user settings in editor, so they should override the build server ones.
    // (This is later turned into a map, so the latter will override the first ones).
    val envVariables = buildServerEnvVariables ++ testClassesEnvVariables

    val resolvedSuites = suites(frameworks.toSeq)
    scribe.debug(
      s"Resolved ${resolvedSuites.size} test suites: ${resolvedSuites.keys.map(_.name()).mkString(", ")}"
    )

    val server =
      new TestServer(handler, loader, resolvedSuites)
    val forkMain = classOf[sbt.ForkMain].getCanonicalName
    val arguments = List(server.port.toString)

    server.listenToTests

    Run.runMain(
      root,
      project.runClassPath.map(_.toNIO) ++ TestInternals.testAgentFiles,
      userJavaHome,
      forkMain,
      arguments,
      jvmOptions,
      envVariables,
      new Logger(listener),
      isDebug,
    )
  }

}

final case class Discovered(
    symbol: String,
    className: String,
    baseClasses: Set[String],
    annotations: Set[String],
    isModule: Boolean,
)
