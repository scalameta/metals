package scala.meta.internal.pc

import java.net.URI
import java.{util as ju}

import scala.collection.concurrent.TrieMap

import scala.meta.internal.mtags.MD5

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.interactive.SourceTree
import dotty.tools.dotc.reporting.Diagnostic
import dotty.tools.dotc.util.SourceFile

class MetalsDriver(
    override val settings: List[String]
) extends InteractiveDriver(settings):

  private def alreadyCompiled(uri: URI, content: Array[Char]): Boolean =
    compilationUnits.get(uri) match
      case Some(unit) if ju.Arrays.equals(unit.source.content(), content) =>
        true
      case _ => false

  override def run(uri: URI, source: SourceFile): List[Diagnostic] =
    if alreadyCompiled(uri, source.content) then Nil
    else super.run(uri, source)

  override def run(uri: URI, sourceCode: String): List[Diagnostic] =
    val contentHash = MD5.compute(sourceCode)
    if alreadyCompiled(uri, sourceCode.toCharArray()) then Nil
    else super.run(uri, sourceCode)

end MetalsDriver
