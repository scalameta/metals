package tests

import scala.meta.internal.io.PathIO
import scala.meta.internal.symtab.GlobalSymbolTable
import scala.meta.io.AbsolutePath
import scala.meta.internal.mtags.SemanticdbClasspath
import scala.meta.io.Classpath

/** Base class for all expect tests.
 *
 * Exposes useful methods to lookup metadata about the input project.
 */
abstract class BaseExpectSuite(val suiteName: String) extends BaseSuite {
  lazy val input = InputProperties.default()

  lazy val symtab = {
    val bootClasspath =
      sys.props
        .collectFirst {
          case (k, v) if k.endsWith(".boot.class.path") => Classpath(v)
        }
        .getOrElse(Classpath(Nil))
    GlobalSymbolTable(
      input.classpath ++ bootClasspath
    )
  }
  final lazy val sourceroot: AbsolutePath =
    AbsolutePath(BuildInfo.sourceroot)
  final lazy val classpath =
    new SemanticdbClasspath(sourceroot, input.classpath)
  def saveExpect(): Unit
}
