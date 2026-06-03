package scala.tools.nsc.classpath

import scala.collection.mutable.ArrayBuffer
import scala.tools.nsc.util.ClassPath
import scala.tools.nsc.util.ClassRepresentation

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

  override private[nsc] def list(inPackage: PackageName): ClassPathEntries = {
    val pkgs = packages(inPackage)
    val classEntries = classes(inPackage)
    val sourceEntries = sources(inPackage)

    // Build a name → class map for merging class+source pairs.
    val classMap = classEntries.iterator.map(c => c.name -> c).toMap
    val mergedClassNames = new java.util.HashSet[String]()
    val result = new ArrayBuffer[ClassRepresentation]()

    // Add sources first. When a class entry exists for the same name, merge once;
    // subsequent sources with the same name are kept as standalone SourceFileEntries.
    for (s <- sourceEntries) {
      classMap.get(s.name) match {
        case Some(c) if mergedClassNames.add(s.name) =>
          result += ClassAndSourceFilesEntry(c.file, s.file)
        case _ =>
          result += s
      }
    }
    // Add class entries that had no matching source.
    for (c <- classEntries if !mergedClassNames.contains(c.name)) {
      result += c
    }

    ClassPathEntries(pkgs, if (result.isEmpty) Nil else result.toIndexedSeq)
  }
}

object MetalsAggregateClassPath {
  def apply(aggregates: Seq[ClassPath]): ClassPath =
    new MetalsAggregateClassPath(aggregates)
}
