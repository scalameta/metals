package scala.meta.internal.pc

import scala.meta.internal.metals.Fuzzy
import scala.meta.internal.semanticdb.Scala._
import java.nio.file.Path

sealed abstract class SymbolSearchCandidate {
  final def nameLength(): Int = Fuzzy.nameLength(nameString)
  final def innerClassDepth: Int =
    SymbolSearchCandidate.characterCount(nameString, termCharacter)
  def termCharacter: Char
  def nameString: String
  def packageString: String
}
object SymbolSearchCandidate {
  final case class Classfile(pkg: String, filename: String)
      extends SymbolSearchCandidate {
    def nameString: String = filename
    override def packageString: String = pkg
    override def termCharacter: Char = '$'
  }
  final case class Workspace(symbol: String, path: Path)
      extends SymbolSearchCandidate {
    def nameString: String = symbol
    override def packageString: String = {
      def loop(s: String): String = {
        if (s.isNone) s
        else if (s.isPackage) s
        else loop(s.owner)
      }
      loop(symbol)
    }
    override def termCharacter: Char = '.'
  }
  class Comparator(query: String)
      extends java.util.Comparator[SymbolSearchCandidate] {
    override def compare(
        o1: SymbolSearchCandidate,
        o2: SymbolSearchCandidate
    ): Int = {
      val byNameLength =
        Integer.compare(o1.nameLength(), o2.nameLength())
      if (byNameLength != 0) byNameLength
      else {
        val byInnerclassDepth =
          Integer.compare(o1.innerClassDepth, o2.innerClassDepth)
        if (byInnerclassDepth != 0) byInnerclassDepth
        else {
          val byFirstQueryCharacter = Integer.compare(
            o1.nameString.indexOf(query.head),
            o2.nameString.indexOf(query.head)
          )
          if (byFirstQueryCharacter != 0) {
            byFirstQueryCharacter
          } else {
            val byPackageDepth = Integer.compare(
              characterCount(o1.packageString, '/'),
              characterCount(o2.packageString, '/')
            )
            if (byPackageDepth != 0) byPackageDepth
            else o1.nameString.compareTo(o2.nameString)
          }
        }
      }
    }
  }
  private def characterCount(string: CharSequence, ch: Char): Int = {
    var i = 0
    var count = 0
    while (i < string.length) {
      if (string.charAt(i) == ch) {
        count += 1
      }
      i += 1
    }
    count
  }
}
