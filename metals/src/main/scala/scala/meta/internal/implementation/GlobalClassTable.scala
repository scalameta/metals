package scala.meta.internal.implementation
import scala.meta.internal.metals.BuildTargets
import scala.meta.io.AbsolutePath
import scala.meta.internal.symtab.GlobalSymbolTable
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.Classpath

final class GlobalClassTable(
    buildTargets: BuildTargets
) {

  def classesFor(source: AbsolutePath): Option[GlobalSymbolTable] = {
    for {
      buildTargetId <- buildTargets.inverseSources(source)
      scalaTarget <- buildTargets.scalaTarget(buildTargetId)
    } yield {
      val classpath = new Classpath(
        scalaTarget.scalac.getClasspath().asScala.map(_.toAbsolutePath).toList
      )
      GlobalSymbolTable(classpath, includeJdk = true)
    }
  }
}
