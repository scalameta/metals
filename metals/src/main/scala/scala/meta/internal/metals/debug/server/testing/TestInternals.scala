package scala.meta.internal.metals.debug.server.testing

import java.nio.file.Path
import java.util.regex.Pattern

import scala.collection.mutable
import scala.util.control.NonFatal

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.debug.server.Discovered

import coursier.Dependency
import org.scalatools.testing.{Framework => OldFramework}
import sbt.testing.AnnotatedFingerprint
import sbt.testing.Fingerprint
import sbt.testing.Framework
import sbt.testing.Runner
import sbt.testing.SubclassFingerprint

final case class FingerprintInfo[+Print <: Fingerprint](
    name: String,
    isModule: Boolean,
    framework: Framework,
    fingerprint: Print,
)

object TestInternals {
  private final val sbtOrg = "org.scala-sbt"
  private final val testAgentId = "test-agent"
  private final val testAgentVersion = BuildInfo.sbtVersion

  val dependency: Dependency =
    Embedded.dependencyOf(sbtOrg, testAgentId, testAgentVersion)

  lazy val testAgentFiles: List[Path] = {
    try {
      Embedded.downloadDependency(dependency, None)
    } catch {
      case NonFatal(e) =>
        scribe.warn(e)
        Nil
    }
  }

  /**
   * Parses `filters` to produce a filtering function for the tests.
   * Only the tests accepted by this filter will be run.
   *
   * `*` is interpreter as wildcard. Each filter can start with `-`, in which case it means
   * that it is an exclusion filter.
   *
   * @param filters A list of strings, representing inclusion or exclusion patterns
   * @return A function that determines whether a test should be run given its FQCN.
   */
  def parseFilters(filters: List[String]): String => Boolean = {
    val (exclusionFilters, inclusionFilters) =
      filters.map(_.trim).partition(_.startsWith("-"))
    val inc = inclusionFilters.map(toPattern)
    val exc = exclusionFilters.map(f => toPattern(f.tail))

    (inc, exc) match {
      case (Nil, Nil) =>
        (_ => true)
      case (inc, Nil) =>
        (s => inc.exists(_.matcher(s).matches))
      case (Nil, exc) =>
        (s => !exc.exists(_.matcher(s).matches))
      case (inc, exc) =>
        (
            s =>
              inc.exists(_.matcher(s).matches) && !exc.exists(
                _.matcher(s).matches
              )
        )
    }
  }

  lazy val filteredLoader: FilteredLoader = {
    val filter = new IncludeClassFilter(
      Set(
        "jdk.", "java.", "javax.", "sun.", "sbt.testing.",
        "org.scalatools.testing.", "org.xml.sax.",
      )
    )
    new FilteredLoader(getClass.getClassLoader, filter)
  }

  def loadFramework(l: ClassLoader, fqns: List[String]): Option[Framework] = {
    fqns match {
      case head :: tail => loadFramework(l, head).orElse(loadFramework(l, tail))
      case Nil => None
    }
  }

  def getFingerprints(
      frameworks: Seq[Framework]
  ): (
      List[FingerprintInfo[SubclassFingerprint]],
      List[FingerprintInfo[AnnotatedFingerprint]],
  ) = {
    // The tests need to be run with the first matching framework, so we use a LinkedHashSet
    // to keep the ordering of `frameworks`.
    val subclasses =
      mutable.LinkedHashSet.empty[FingerprintInfo[SubclassFingerprint]]
    val annotated =
      mutable.LinkedHashSet.empty[FingerprintInfo[AnnotatedFingerprint]]
    for {
      framework <- frameworks
      fingerprint <- framework.fingerprints()
    } fingerprint match {
      case sub: SubclassFingerprint =>
        subclasses += FingerprintInfo(
          sub.superclassName,
          sub.isModule,
          framework,
          sub,
        )
      case ann: AnnotatedFingerprint =>
        annotated += FingerprintInfo(
          ann.annotationName,
          ann.isModule,
          framework,
          ann,
        )
    }
    (subclasses.toList, annotated.toList)
  }

  // Slightly adapted from sbt/sbt
  def matchingFingerprints(
      subclassPrints: List[FingerprintInfo[SubclassFingerprint]],
      annotatedPrints: List[FingerprintInfo[AnnotatedFingerprint]],
      d: Discovered,
  ): List[FingerprintInfo[Fingerprint]] = {
    defined(subclassPrints, d.baseClasses, d.isModule) ++
      defined(annotatedPrints, d.annotations, d.isModule)
  }

  def getRunner(
      framework: Framework,
      testClassLoader: ClassLoader,
  ): Runner = framework.runner(Array.empty, Array.empty, testClassLoader)

  // Slightly adapted from sbt/sbt
  private def defined[T <: Fingerprint](
      in: List[FingerprintInfo[T]],
      names: Set[String],
      IsModule: Boolean,
  ): List[FingerprintInfo[T]] = {
    in.collect {
      case info @ FingerprintInfo(name, IsModule, _, _) if names(name) => info
    }
  }

  private def loadFramework(
      loader: ClassLoader,
      fqn: String,
  ): Option[Framework] = {
    try {
      Class
        .forName(fqn, true, loader)
        .getDeclaredConstructor()
        .newInstance() match {
        case framework: Framework => Some(framework)
        case _: OldFramework =>
          scribe.warn(s"Old frameworks are not supported: $fqn"); None
      }
    } catch {
      case _: ClassNotFoundException => None
      case NonFatal(t) =>
        scribe.error(s"Initialisation of test framework $fqn failed", t)
        None
    }
  }

  /**
   * Converts the input string to a compiled `Pattern`.
   *
   * The string is split at `*` (representing wildcards).
   *
   * @param filter The input filter
   * @return The compiled pattern matching the input filter.
   */
  private def toPattern(filter: String): Pattern = {
    val parts = filter
      .split("\\*", -1)
      .map { // Don't discard trailing empty string, if any.
        case "" => ""
        case str => Pattern.quote(str)
      }
    Pattern.compile(parts.mkString(".*"))
  }

}
