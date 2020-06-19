package tests

import scala.meta.internal.mtags.SemanticdbClasspath
import scala.meta.internal.symtab.GlobalSymbolTable
import scala.meta.io.AbsolutePath
import scala.meta.io.Classpath

/**
 * Base class for all expect tests.
 *
 * Exposes useful methods to lookup metadata about the input project.
 */
abstract class BaseExpectSuite(val suiteName: String) extends BaseSuite {
  lazy val input: InputProperties = InputProperties.default()

  lazy val symtab: GlobalSymbolTable = {
    val bootClasspath =
      sys.props
        .collectFirst {
          case (k, v) if k.endsWith(".boot.class.path") => Classpath(v)
        }
        .getOrElse(Classpath(Nil))
    GlobalSymbolTable(
      input.classpath ++ bootClasspath,
      includeJdk = true
    )
  }
  final lazy val sourceroot: AbsolutePath =
    AbsolutePath(BuildInfo.sourceroot)
  final lazy val classpath =
    new SemanticdbClasspath(sourceroot, input.classpath)
  def saveExpect(): Unit
}
