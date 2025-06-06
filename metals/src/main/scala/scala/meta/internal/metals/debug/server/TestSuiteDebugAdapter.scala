package scala.meta.internal.metals.debug.server

import java.net.URLClassLoader

import scala.collection.mutable
import scala.concurrent.ExecutionContext

import scala.meta.internal.metals.JdkSources
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
import ch.epfl.scala.debugadapter.JavaRuntime
import ch.epfl.scala.debugadapter.Library
import ch.epfl.scala.debugadapter.Module
import ch.epfl.scala.debugadapter.UnmanagedEntry
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
    extends MetalsDebuggee() {

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

  override def modules: Seq[Module] = project.modules
  override def libraries: Seq[Library] = project.libraries
  override def unmanagedEntries: Seq[UnmanagedEntry] = project.unmanagedEntries
  override protected def scalaVersionOpt: Option[String] = project.scalaVersion

  override val javaRuntime: Option[JavaRuntime] =
    JdkSources
      .defaultJavaHome(userJavaHome)
      .flatMap(path => JavaRuntime(path.toNIO))
      .headOption

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
    val testClassesEnvOptions =
      testClasses.getEnvironmentVariables().asScala.toList
    val buildServerEnvOptions = project.environmentVariables.iterator.map {
      case (k, v) => s"$k=$v"
    }.toList
    val envOptions = buildServerEnvOptions  ++ testClassesEnvOptions
    scribe.debug(
      s"""|Environment variables for the test suite: 
          |  ${envOptions.mkString("\n  ")}""".stripMargin
    )

    scribe.debug("Starting forked test execution...")
    val resolvedSuites = suites(frameworks.toSeq)

    val server =
      new TestServer(handler, loader, resolvedSuites)
    val forkMain = classOf[sbt.ForkMain].getCanonicalName
    val arguments = List(server.port.toString)
    val testAgentJars =
      TestInternals.testAgentFiles.filter(_.toString.endsWith(".jar"))
    scribe.debug("Test agent JARs: " + testAgentJars.mkString(", "))

    server.listenToTests

    scribe.debug(s"""|Running test with debugger with compile classpath:
                     |\t${classPath.mkString("\n\t")}
                     |and run classpath:
                     |\t${project.runClassPath.mkString("\n\t")}""".stripMargin)

    Run.runMain(
      root,
      project.runClassPath.map(_.toNIO) ++ testAgentJars,
      userJavaHome,
      forkMain,
      arguments,
      jvmOptions,
      envOptions,
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
