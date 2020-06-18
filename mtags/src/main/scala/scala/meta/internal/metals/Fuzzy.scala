package scala.meta.internal.metals

import scala.annotation.tailrec
import scala.collection.mutable

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
 * - main name, the last name in the query or symbol. For example, "Pos" is the main name in "s.m.Pos".
 * - qa, the start index in the query string.
 * - qb, the end index in the query string.
 * - sa, the start index in the symbol string.
 * - sb, the end index in the symbol string.
 */
object Fuzzy extends Fuzzy
class Fuzzy {
  private class Delimiter(
      val isFinished: Boolean,
      val idx: Int
  )

  /**
   * Returns true if the query matches the given symbol.
   *
   * @param query the search query like "m.Pos"
   * @param symbol the symbol to test the query against like "scala/meta/inputs/Position#"
   * @param skipNames the number of names in the symbol to jump over. For regular search,
   *                  use 0. Use 1 to let the query "m.Pos" match "scala/meta/Position#Range."
   */
  def matches(
      query: CharSequence,
      symbol: CharSequence,
      skipNames: Int = 0
  ): Boolean = {
    val li = lastIndex(symbol)
    // Loops through all names in the query/symbol strings in reverse order (last names first)
    // and returns true if all query names match their corresponding symbol name.
    // For the query "col.imm.Li" and symbol "scala/collection/immutable/List" we do the following loops.
    // Loop 1: compareNames("Li", "List")
    // Loop 2: compareNames("imm", "immutable")
    // Loop 3: compareNames("col", "collection")
    @tailrec
    def loopDelimiters(qb: Int, sb: Int, depth: Int, skip: Int): Boolean = {
      val qd = lastDelimiter(query, qb)
      val sd = lastDelimiter(symbol, sb)
      if (skip > 0) {
        loopDelimiters(qb, sd.idx - 1, depth, skip - 1)
      } else {
        val isMatch = matchesName(query, qd.idx, qb, symbol, sd.idx, sb)
        if (isMatch) {
          if (qd.isFinished) {
            true
          } else if (sd.isFinished) {
            false
          } else {
            loopDelimiters(qd.idx - 1, sd.idx - 1, depth + 1, skip - 1)
          }
        } else if (sb == li && exactMatch("package", symbol, sd.idx, sb)) {
          // If last symbol name does not match and the symbol name
          // is "package" skip symbol name.
          // This allows "scala.concurrent" to match "scala/concurrent/package"
          loopDelimiters(qb, sd.idx - 1, depth, skip)
        } else if (depth > 0 && !sd.isFinished) {
          // Hop over the symbol name if the main query/symbol names match, this allows
          // the query "m.Pos" to match the symbol "scala/meta/inputs/Position".
          loopDelimiters(qb, sd.idx - 1, depth, skip - 1)
        } else {
          false
        }
      }
    }
    loopDelimiters(
      query.length,
      lastIndex(symbol),
      0,
      skipNames
    )
  }

  private def exactMatch(
      query: CharSequence,
      symbol: CharSequence,
      sa: Int,
      sb: Int
  ): Boolean = {
    if (query.length == sb - sa) {
      var idx = 0
      while (idx < query.length) {
        if (query.charAt(idx) != symbol.charAt(sa + idx)) return false
        idx += 1
      }
      true
    } else {
      false
    }
  }

  def isExactMatch(query: String, filename: CharSequence): Boolean = {
    val sb = lastIndex(filename)
    val sa = sb - query.length()
    if (sa < 0) {
      false
    } else {
      exactMatch(query, filename, sa, sb)
    }
  }

  private def lastIndex(symbol: CharSequence): Int = {
    var end = symbol.length() - (if (endsWith(symbol, ".class"))
                                   ".class".length
                                 else 1)
    while (end >= 0 && isDelimiter(symbol.charAt(end))) {
      end -= 1
    }
    end + 1
  }

  def isDelimiter(ch: Char): Boolean =
    ch match {
      case '.' | '/' | '#' | '$' => true
      case _ => false
    }

  /**
   * Returns the length of the last name in this symbol.
   *
   * Example: scala/Option$Some.class returns length of "Some"
   */
  def nameLength(symbol: CharSequence): Int = {
    val end = lastIndex(symbol) - 1
    var start = end
    while (start >= 0 && !isDelimiter(symbol.charAt(start))) {
      start -= 1
    }
    if (start < 0) end + 1
    else end - start
  }

  def endsWith(cs: CharSequence, string: String): Boolean = {
    val a = cs.length() - 1
    val b = string.length() - 1
    if (b > a) false
    else if (b == 0) false
    else {
      var i = 0
      while (i <= a && i <= b) {
        if (
          cs.charAt(a - i) !=
            string.charAt(b - i)
        ) return false
        i += 1
      }
      true
    }
  }

  private def lastDelimiter(
      string: CharSequence,
      fromIndex: Int
  ): Delimiter = {
    var curr = fromIndex - 2
    var continue = true
    while (curr >= 0 && continue) {
      if (isDelimiter(string.charAt(curr))) {
        continue = false
      } else {
        curr -= 1
      }
    }
    if (curr < 0) new Delimiter(true, 0)
    else new Delimiter(false, curr + 1)
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
    // @param ql the last index in query at which qa.isUpper && charAt(qa) == charAt(sa)
    // @param sl the last index in symbol at which qa.isUpper && charAt(qa) == charAt(sa)
    // For the query "Stop" and symbol "MStartStop" we do the following iterations:
    // Loop 1: 'S' 'M'       , no match, increment symbol start index
    // Loop 2: 'S' 'S' (1st) , match, increment both indexes, update ql and sl
    // Loop 3: 't' 't'       , match, increment both indexes, but don't update ql and sl
    // Loop 4: 'o' 'a'       , no match, backtrack to ql and sl + 1
    // Loop 5: 'S' 'S' (2nd) , match, increment both indexes, update ql and sl
    // Loop 6: 't' 't'       , match, increment both indexes but don't update ql and sl
    // Loop 7: 'o' 'o'       , match, ...
    // Loop 8: 'p' 'p'       , match, ...
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
      pkg: String = ""
  ): StringBloomFilter = {
    val estimatedSize = symbols.foldLeft(0) {
      case (accum, string) =>
        val redundantSuffix =
          if (string.endsWith(".class")) ".class".length()
          else 0
        val uppercases = string.count(_.isUpper)
        accum + string.length() +
          TrigramSubstrings.trigramCombinations(uppercases) -
          redundantSuffix
    }
    val hasher = new StringBloomFilter(estimatedSize)
    bloomFilterSymbolStrings(symbols, hasher)
    hasher
  }

  /**
   * Returns the set of strings to insert into a bloom filter index of a single package or file.
   *
   * Given a query and set of symbols where there exists at least one symbol where `Fuzzy.matches(query, symbol)`,
   * this method must meet the following constraints:
   *   predicate `symbols.exists(symbol => Fuzzy.matches(query, symbol))`
   *   implies `bloomFilterQueryStrings(query).forall(bloom.mightContain)`
   *   where `bloom = BloomFilter(bloomFilterSymbolStrings)`
   *
   * What this method roughly tries to achieve is extract the substrings of the symbols that can appear in queries.
   * For example, given the symbol `InputFileChunkedStream` we insert the following substrings:
   *
   * - All prefixes of the individual names `Input`, `File`, `Chunked` and `Stream`,
   *   example: "I", "In", "Inp", ..., "Strea", "Stream".
   * - All trigrams of uppercase characters, example: "IFC", "IFS", "FCS".
   *
   * @param symbols all symbols in a source file or a package.
   */
  def bloomFilterSymbolStrings(
      symbol: String,
      hasher: StringBloomFilter
  ): Unit = {
    if (symbol.endsWith("$sp.class")) return
    hasher.reset()
    var i = 0
    var delimiter = i
    val upper = new StringBuilder()
    var symbolicDelimiter = i
    val N = lastIndex(symbol)
    while (i < N) {
      val ch = symbol.charAt(i)
      ch match {
        case '.' | '/' | '#' | '$' =>
          hasher.reset()
          delimiter = i + 1
          symbolicDelimiter = delimiter
        case _ =>
          if (ch.isUpper) {
            delimiter = i
            hasher.reset()
            upper.append(ch)
          }
          hasher.putCharIncrementally(ch)
      }
      i += 1
    }
    val lastName = new ZeroCopySubSequence(symbol, symbolicDelimiter, N)
    if (
      !symbol.endsWith("/") &&
      !isAllNumeric(lastName) &&
      lastName.length() < ExactSearchLimit
    ) {
      hasher.putCharSequence(ExactCharSequence(lastName))
    }
    TrigramSubstrings.foreach(
      upper.toString,
      trigram => hasher.putCharSequence(trigram)
    )
  }

  def bloomFilterSymbolStrings(
      symbols: Iterable[String],
      hasher: StringBloomFilter
  ): Unit = {
    symbols.foreach(sym => bloomFilterSymbolStrings(sym, hasher))
  }

  /**
   * Returns true if this char sequence contains only digit characters.
   */
  def isAllNumeric(string: CharSequence): Boolean = {
    var i = 0
    val n = string.length()
    while (i < n) {
      if (!string.charAt(i).isDigit) return false
      i += 1
    }
    true
  }

  /**
   * Queries fewer characters than this variable are treated as exact searches.
   *
   * For example, the query "S" returns only symbols with the exact name "S" and not symbols
   * like "Stream".
   */
  val ExactSearchLimit = 3

  /**
   * Companion to `bloomFilterSymbolStrings`.
   */
  def bloomFilterQueryStrings(
      query: String,
      includeTrigrams: Boolean = true
  ): Iterable[CharSequence] = {
    if (query.length < ExactSearchLimit) {
      List(ExactCharSequence(query))
    } else {
      val result = mutable.Set.empty[CharSequence]
      val upper = new StringBuilder
      var i = 0
      var border = 0
      while (i < query.length) {
        val ch = query.charAt(i)
        ch match {
          case '.' | '/' | '#' | '$' =>
            result.add(new ZeroCopySubSequence(query, border, i))
            border = i + 1
          case _ =>
            if (ch.isUpper) {
              if (border != i) {
                val exactName = new ZeroCopySubSequence(query, border, i)
                result.add(exactName)
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
          result.add(new ZeroCopySubSequence(query, border, query.length))
      }
      if (includeTrigrams) {
        TrigramSubstrings.foreach(upper.toString, trigram => result += trigram)
      }
      result
    }
  }

  /**
   * Returns true if all characters in the query have a case in-sensitive matching character in the symbol, in-order
   *
   * Matching examples:
   * - int       toInt
   * - int       instance  // Because `in` and `t`
   * - int       intNumber
   *
   * Non-matching examples:
   * - int       inSub // missing t
   *
   * @param query the query string, like "int"
   * @param sym the symbol to test for matching against the query string, like "toInt".
   */
  def matchesSubCharacters(query: CharSequence, sym: CharSequence): Boolean = {
    val A = query.length()
    val B = sym.length()
    def loop(a: Int, b: Int): Boolean = {
      if (a >= A) true
      else if (b >= B) false
      else {
        val ca = query.charAt(a).toLower
        val cb = sym.charAt(b).toLower
        if (ca == cb) loop(a + 1, b + 1)
        else if (cb == '$') false
        else loop(a, b + 1)
      }
    }
    loop(0, 0)
  }

}
