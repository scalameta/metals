package scala.meta.internal.metals

import ch.epfl.scala.bsp4j.ScalaBuildTarget
import ch.epfl.scala.bsp4j.ScalacOptionsItem
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.pc.ScalaPresentationCompiler
import scala.meta.pc.PresentationCompiler
import scala.meta.pc.SymbolIndexer
import scala.meta.pc.SymbolSearch
import scala.util.Properties

case class BuildTargetCompiler(pc: PresentationCompiler, search: SymbolSearch)
    extends Cancelable {
  override def cancel(): Unit = pc.shutdown()
}

object BuildTargetCompiler {
  def fromClasspath(
      scalac: ScalacOptionsItem,
      info: ScalaBuildTarget,
      indexer: SymbolIndexer,
      search: SymbolSearch,
      embedded: Embedded
  ): BuildTargetCompiler = {
    val classpath = scalac.classpath.map(_.toNIO).toSeq
    val pc: PresentationCompiler =
      if (info.getScalaVersion == Properties.versionNumberString) {
        new ScalaPresentationCompiler()
      } else {
        embedded.presentationCompiler(info, scalac)
      }
    BuildTargetCompiler(
      pc.withIndexer(indexer)
        .withSearch(search)
        .newInstance(
          scalac.getTarget.getUri,
          classpath.asJava,
          scalac.getOptions
        ),
      search
    )
  }
}
