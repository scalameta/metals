package scala.meta.internal.metals

import scala.annotation.tailrec
import scala.collection.mutable
import java.lang.StringBuilder

/**
 * Metals fuzzy search for strings.
 *
 * Goals:
 * - predictable, the user should understand why particular results matched
 *   a given query. When the search is too fuzzy the results become noisy
 *   and the user has little control over how to narrow the results.
 * - fast, we perform fuzzy search on a lot of string in critical paths
 *   on ever user keystroke. We avoid allocations and backtracking when
 *   possible even if it comes at the price of less readable code.
 *
 * The following pairs of (query, symbol) match.
 * - InStr     java/io/InputFileStream#
 * - IFS       java/io/InputFileStream#
 * - i.InStr   java/io/InputFileStream#
 * - j.i.InStr java/io/InputFileStream#
 * - M.Entry   java/util/Map#Entry#      (inner classes are like packages)
 *
 * The following pairs of (query, symbol) do not match.
 * - IpStr     java/io/InputFileStream# (missing leading `n` before `p`)
 * - instr     java/io/InputFileStream# (lowercase queries are exact, WorkspaceSymbolProvider works around this
 *                                       limitation by guessing multiple capitalizations of all-lowercase queries)
 * - MapEntry  java/io/InputFileStream# (missing `.` separator after "Map")
 * - j.InStr   java/io/InputFileStream# (missing `io` separator, the `java/` package must be direct parent)
 *
 * Glossary and conventions used in this file:
 * - query, what the user typed to look up a symbol.
 * - symbol, a SemanticDB Java/Scala symbol (https://scalameta.org/docs/semanticdb/specification.html)
 *   or a java.util.zip.ZipEntry name pointing to a classfile.
 * - delimiter, one of the characters '.' or '/' or '#' that separate package/class/object/trait names
 *   in SemanticDB symbols, or '$' that separates inner classes in classfile names.
 * - name, characters between delimiters like "io" in "java/io/InputStream".
 * - qa, the start index in the query string.
 * - qb, the end index in the query string.
 * - sa, the start index in the symbol string.
 * - sb, the end index in the symbol string.
 */
object Fuzzy {

  /**
   * Returns true if the query matches the given symbol.
   */
  def matches(
      query: CharSequence,
      symbol: CharSequence
  ): Boolean = {
    def lastDelimiter(
        string: CharSequence,
        fromIndex: Int
    ): (Boolean, Int) = {
      var curr = fromIndex - 2
      var continue = true
      while (curr >= 0 && continue) {
        string.charAt(curr) match {
          case '.' | '/' | '#' | '$' =>
            continue = false
          case _ =>
            curr -= 1
        }
      }
      if (curr < 0) (true, 0)
      else (false, curr + 1)
    }
    // Loops through all names in the query/symbol strings in reverse order (last names first)
    // and returns true if all query names match their corresponding symbol name.
    @tailrec
    def loopDelimiters(qb: Int, sb: Int): Boolean = {
      val (isEndOfQuery, qa) = lastDelimiter(query, qb)
      val (isEndOfSymbol, sa) = lastDelimiter(symbol, sb)
      val isMatch = matchesName(query, qa, qb, symbol, sa, sb)
      if (!isMatch) {
        false
      } else if (isEndOfQuery) {
        true
      } else if (isEndOfSymbol) {
        false
      } else {
        loopDelimiters(qa - 1, sa - 1)
      }
    }
    val endOfSymbolDelimiter = symbol.charAt(symbol.length - 1) match {
      case '.' | '/' | '#' | '$' => 1
      case _ => 0
    }
    loopDelimiters(query.length, symbol.length - endOfSymbolDelimiter)
  }

  // Compares two names like query "InStr" and "InputFileStream".
  // The substring are guaranteed to not have delimiters.
  private def matchesName(
      query: CharSequence,
      qa: Int,
      qb: Int,
      symbol: CharSequence,
      sa: Int,
      sb: Int
  ): Boolean = {
    /*
     * @param ql the last index in query at which qa.isUpper && charAt(qa) == charAt(sa)
     * @param ll the last index in symbol at which qa.isUpper && charAt(qa) == charAt(sa)
     * @return
     */
    @tailrec
    def loop(qa: Int, ql: Int, sa: Int, sl: Int): Boolean = {
      if (qa >= qb) {
        true
      } else if (sa >= sb) {
        false
      } else {
        val qq = query.charAt(qa)
        val ss = symbol.charAt(sa)
        if (qq == ss) {
          val qll = if (qq.isUpper) qa else ql
          val sll = if (ss.isUpper) sa else sl
          loop(qa + 1, qll, sa + 1, sll)
        } else if (qq.isLower) {
          if (sl < 0 || ql < 0) false
          else {
            // Backtrack to ql and sl + 1, happens for example in query "Stop" for symbol "SStop",
            // we backtrack because the first two `S` should not align together.
            loop(ql, -1, sl + 1, -1)
          }
        } else {
          loop(qa, ql, sa + 1, sl)
        }
      }
    }
    loop(qa, -1, sa, -1)
  }

  def bloomFilterSymbolStrings(
      symbols: Iterable[String],
      result: mutable.Set[CharSequence] = mutable.Set.empty
  ): mutable.Set[CharSequence] = {
    def visit(symbol: String): Unit = {
      var i = 0
      var delimiter = i
      val upper = new StringBuilder()
      while (i < symbol.length) {
        val ch = symbol.charAt(i)
        ch match {
          case '.' | '/' | '#' | '$' =>
            delimiter = i + 1
          case _ =>
            if (ch.isUpper) {
              delimiter = i
              upper.append(ch)
            }
            result.add(symbol.subSequence(delimiter, i + 1))
        }
        i += 1
      }
      result ++= new TrigramSubstrings(upper.toString)
    }
    symbols.foreach(visit)
    result
  }

  def bloomFilterQueryStrings(
      query: String,
      includeTrigrams: Boolean = true
  ): Iterable[CharSequence] = {
    val result = mutable.Set.empty[CharSequence]
    val upper = new StringBuilder
    var i = 0
    var border = 0
    while (i < query.length) {
      val ch = query.charAt(i)
      ch match {
        case '.' | '/' | '#' | '$' =>
          result.add(query.subSequence(border, i))
          border = i + 1
        case _ =>
          if (ch.isUpper) {
            if (border != i) {
              result.add(query.subSequence(border, i))
            }
            upper.append(ch)
            border = i
          }
      }
      i += 1
    }
    query.last match {
      case '.' | '/' | '#' | '$' =>
      case _ =>
        result.add(query.subSequence(border, query.length))
    }
    if (includeTrigrams) {
      result ++= new TrigramSubstrings(upper.toString)
    }
    result
  }

}
