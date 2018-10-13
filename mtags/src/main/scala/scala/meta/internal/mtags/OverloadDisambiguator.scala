package scala.meta.internal.mtags

import scala.collection.mutable

/**
 * Utility to generate method symbol disambiguators according to SemanticDB spec.
 *
 * See https://scalameta.org/docs/semanticdb/specification.html#scala-symbol
 */
final class OverloadDisambiguator(
    names: mutable.Map[String, Int] = mutable.Map.empty
) {
  def disambiguator(name: String): String = {
    val n = names.getOrElseUpdate(name, 0)
    names(name) = n + 1
    if (n == 0) "()"
    else s"(+$n)"
  }
}
