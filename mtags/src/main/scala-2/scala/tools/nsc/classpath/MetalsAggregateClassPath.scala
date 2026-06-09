package scala.tools.nsc.classpath

import scala.collection.mutable.ArrayBuffer
import scala.reflect.internal.FatalError
import scala.tools.nsc.util.ClassPath
import scala.tools.nsc.util.ClassRepresentation
import scala.tools.nsc.util.EfficientClassPath

/**
 * A variant of AggregateClassPath that does not deduplicate source file entries
 * by basename. This allows the presentation compiler to see multiple source files
 * with the same basename in the same package (e.g. main/util/Utils.scala and
 * test/util/Utils.scala both in package util), which can occur in MBT-based builds
 * where multiple source roots contribute to a single logical package.
 */
final class MetalsAggregateClassPath(_aggregates: Seq[ClassPath])
    extends AggregateClassPath(_aggregates) {

  override private[nsc] def sources(
      inPackage: PackageName
  ): Seq[SourceFileEntry] =
    aggregates.flatMap(_.sources(inPackage))

  // The same as in AggregateClassPath, except it now uses mergeWithDuplicateSources
  override private[nsc] def list(inPackage: PackageName): ClassPathEntries = {
    val packages: java.util.HashSet[PackageEntry] =
      new java.util.HashSet[PackageEntry]()
    val classesAndSourcesBuffer = new ArrayBuffer[ClassRepresentation]()
    val onPackage: PackageEntry => Unit = packages.add(_)
    val onClassesAndSources: ClassRepresentation => Unit =
      classesAndSourcesBuffer += _

    aggregates.foreach { cp =>
      try {
        cp match {
          case ecp: EfficientClassPath =>
            ecp.list(inPackage, onPackage, onClassesAndSources)
          case _ =>
            val entries = cp.list(inPackage)
            entries._1.foreach(entry => packages.add(entry))
            classesAndSourcesBuffer ++= entries._2
        }
      } catch {
        case ex: java.io.IOException =>
          val e = FatalError(ex.getMessage)
          e.initCause(ex)
          throw e
      }
    }

    val distinctPackages: Seq[PackageEntry] =
      if (packages.isEmpty) Nil
      else
        packages.toArray(new Array[PackageEntry](packages.size())).toIndexedSeq
    val mergedClassesAndSources = mergeWithDuplicateSources(
      classesAndSourcesBuffer
    )
    ClassPathEntries(distinctPackages, mergedClassesAndSources)
  }

  // Like AggregateClassPath.mergeClassesAndSources
  // but keeps all source entries with the same name
  private def mergeWithDuplicateSources(
      entries: ArrayBuffer[ClassRepresentation]
  ): Seq[ClassRepresentation] = {
    var count = 0
    val indices =
      new java.util.HashMap[String, Int]((entries.size * 1.25).toInt)
    val mergedEntries = new ArrayBuffer[ClassRepresentation](entries.size)

    for (entry <- entries) {
      val name = entry.name
      if (indices.containsKey(name)) {
        val index = indices.get(name)
        val existing = mergedEntries(index)

        if (existing.binary.isEmpty && entry.binary.isDefined)
          mergedEntries(index) =
            ClassAndSourceFilesEntry(entry.binary.get, existing.source.get)
        else if (existing.source.isEmpty && entry.source.isDefined)
          mergedEntries(index) =
            ClassAndSourceFilesEntry(existing.binary.get, entry.source.get)
        else if (entry.source.isDefined)
          // added in the reimplementation here, to not filter out duplicate sources
          mergedEntries += entry
      } else {
        indices.put(name, count)
        mergedEntries += entry
        count += 1
      }
    }

    if (mergedEntries.isEmpty) Nil else mergedEntries.toIndexedSeq
  }
}

object MetalsAggregateClassPath {
  def apply(aggregates: Seq[ClassPath]): ClassPath =
    new MetalsAggregateClassPath(aggregates)
}
