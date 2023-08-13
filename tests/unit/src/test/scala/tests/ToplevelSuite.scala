package tests

import java.nio.charset.StandardCharsets

import scala.collection.mutable.ListBuffer

import scala.meta.Dialect
import scala.meta.dialects.Scala213
import scala.meta.dialects.Scala3
import scala.meta.inputs.Input
import scala.meta.internal.io.FileIO
import scala.meta.internal.mtags.Mtags

/**
 * Assert that Mtags.toplevels method works as expected.
 */
abstract class ToplevelSuite(
    input: InputProperties,
    dialect: Dialect,
    filename: String,
) extends SingleFileExpectSuite(filename) {
  override def obtained(): String = {
    val toplevels = ListBuffer.empty[String]
    val missingSymbols = ListBuffer.empty[String]
    def forInput(input: InputProperties, dialect: Dialect) = {
      input.sourceDirectories.filter(_.isDirectory).foreach { dir =>
        val ls = FileIO.listAllFilesRecursively(dir)
        ls.files.foreach { relpath =>
          val text =
            FileIO.slurp(ls.root.resolve(relpath), StandardCharsets.UTF_8)
          val input = Input.VirtualFile(relpath.toURI(false).toString, text)
          val reluri = relpath.toURI(isDirectory = false).toString
          Mtags.topLevelSymbols(input, dialect).foreach { toplevel =>
            // do not check symtab for Scala 3 since it's not possible currently
            if (symtab.info(toplevel).isEmpty && dialect != Scala3) {
              missingSymbols += toplevel
            }
            toplevels += s"$reluri -> $toplevel"
          }
        }
      }
    }
    forInput(input, dialect)
    if (missingSymbols.nonEmpty) {
      fail(s"missing symbols:\n${missingSymbols.mkString("\n")}")
    }
    toplevels.sorted.mkString("\n")
  }
}

class ToplevelsScala3Suite
    extends ToplevelSuite(
      InputProperties.scala3(),
      Scala3,
      "toplevels-scala3.expect",
    )

class ToplevelsScala2Suite
    extends ToplevelSuite(
      InputProperties.scala2(),
      Scala213,
      "toplevels.expect",
    )
