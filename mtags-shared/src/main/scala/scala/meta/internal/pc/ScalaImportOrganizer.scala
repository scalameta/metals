package scala.meta.internal.pc

import org.eclipse.{lsp4j => l}

/**
 * Helper class to intelligently insert Scala imports at the best location.
 * Takes inspiration from JavaAutoImportEditor but handles Scala-specific
 * features like grouped imports (including multiline).
 *
 * @param text Source code of the Scala file.
 * @param importBlockStart The start offset of the import block (after package declaration).
 * @param importBlockEnd The end offset of the import block.
 * @param indent The indentation to use for the import.
 */
class ScalaImportOrganizer(
    text: String,
    importBlockStart: Int,
    importBlockEnd: Int,
    indent: String
) {

  /**
   * Compute a text edit that inserts the given import at the best location.
   *
   * Strategy:
   * 1. Group imports into blank-line-separated sections
   * 2. Pick the best section based on top-level package (matching first package segment)
   * 3. Within that section:
   *    a. If there's a matching grouped import {a, b, c}, add to it alphabetically
   *    b. Otherwise, find the import with the longest shared prefix and insert nearby
   *    c. If no prefix match, insert in alphabetical order
   *
   * @param fqn The fully qualified name of the symbol to import, e.g. "scala.collection.mutable.Map"
   *            May include _root_. prefix which will be stripped for matching purposes.
   * @return A text edit to insert the import, or None if the import already exists
   */
  def textEdit(fqn: String): Option[l.TextEdit] = {
    val importLines =
      ScalaImportLine.fromText(text, importBlockStart, importBlockEnd)
    if (importLines.isEmpty) {
      // No existing imports, insert at the import block start
      Some(createEditAtOffset(importBlockStart, fqn, insertBefore = true))
    } else {
      // Strip _root_. prefix for matching purposes
      val normalizedFqn = fqn.stripPrefix("_root_.")
      val owner = normalizedFqn.split('.').dropRight(1).mkString(".")
      val name = normalizedFqn.split('.').last
      val topLevelPackage = normalizedFqn.split('.').headOption.getOrElse("")

      // Group imports into blank-line-separated sections
      val sections = groupIntoSections(importLines)

      // Pick the best section based on top-level package
      val targetSection = pickSection(sections, topLevelPackage)

      // Within the section, try to add to a grouped import or insert alphabetically
      val groupedImports = targetSection.filter(_.isGrouped)
      val matchingGrouped = groupedImports.find(line =>
        normalizeOwner(line.owner) == normalizeOwner(owner)
      )

      matchingGrouped match {
        case Some(groupedImport) =>
          addToGroupedImport(groupedImport, name)
        case None =>
          // Insert alphabetically within the section
          insertInSection(targetSection, fqn)
      }
    }
  }

  /**
   * Group imports into sections separated by blank lines.
   */
  private def groupIntoSections(
      importLines: Seq[ScalaImportLine]
  ): Seq[Seq[ScalaImportLine]] = {
    if (importLines.isEmpty) return Seq.empty

    val sections = Seq.newBuilder[Seq[ScalaImportLine]]
    var currentSection = Seq.newBuilder[ScalaImportLine]
    var prevEndOffset = importLines.head.lineStartOffset

    for (imp <- importLines) {
      // Check if there's a blank line between previous import and this one
      val textBetween = text.substring(prevEndOffset, imp.lineStartOffset)
      val hasBlankLine = textBetween.count(_ == '\n') > 1

      if (hasBlankLine && currentSection.result().nonEmpty) {
        sections += currentSection.result()
        currentSection = Seq.newBuilder[ScalaImportLine]
      }

      currentSection += imp
      prevEndOffset = imp.importEndOffset
    }

    val lastSection = currentSection.result()
    if (lastSection.nonEmpty) {
      sections += lastSection
    }

    sections.result()
  }

  /**
   * Pick the best section for the given top-level package.
   * Prefers sections that already have imports from the same top-level package.
   */
  private def pickSection(
      sections: Seq[Seq[ScalaImportLine]],
      topLevelPackage: String
  ): Seq[ScalaImportLine] = {
    if (sections.isEmpty) return Seq.empty
    if (sections.size == 1) return sections.head

    // Find a section that contains imports from the same top-level package
    val matchingSection = sections.find { section =>
      section.exists { imp =>
        val impTopLevel = imp.owner.split('.').headOption.getOrElse("")
        impTopLevel == topLevelPackage
      }
    }

    matchingSection.getOrElse(sections.last)
  }

  /**
   * Insert import near the most similar existing import based on package prefix.
   * This keeps related imports (e.g., java.util.*) together.
   */
  private def insertInSection(
      section: Seq[ScalaImportLine],
      fqn: String
  ): Option[l.TextEdit] = {
    // Find the import with the longest shared prefix
    val withPrefixLengths = section.map { imp =>
      (imp, prefixMatchLength(imp.owner, fqn))
    }

    val maxPrefixLength = withPrefixLengths.map(_._2).max

    if (maxPrefixLength > 0) {
      // Find the LAST import with the maximum prefix length
      // Insert after it to keep similar imports grouped together
      val bestMatch = withPrefixLengths.filter(_._2 == maxPrefixLength).last._1
      Some(
        createEditAtOffset(bestMatch.importEndOffset, fqn, insertBefore = false)
      )
    } else {
      // No prefix match - insert in alphabetical order
      insertAlphabetically(section, fqn)
    }
  }

  /**
   * Insert import in alphabetical order within a section.
   */
  private def insertAlphabetically(
      section: Seq[ScalaImportLine],
      fqn: String
  ): Option[l.TextEdit] = {
    val newImportStr = s"import $fqn"

    // Find the first import that is alphabetically greater than the new import
    val insertPoint = section.find { imp =>
      val existingImportStr =
        if (imp.isGrouped) s"import ${imp.owner}."
        else imp.line.trim
      newImportStr.compareTo(existingImportStr) < 0
    }

    insertPoint match {
      case Some(imp) =>
        // Insert before this import
        Some(createEditAtOffset(imp.lineStartOffset, fqn, insertBefore = true))
      case None =>
        // New import is alphabetically last - append at the end
        val last = section.last
        Some(
          createEditAtOffset(last.importEndOffset, fqn, insertBefore = false)
        )
    }
  }

  /**
   * Calculate how many characters of the package prefix match.
   * For example, "java.util" and "java.util.UUID" share "java.util" (9 chars).
   */
  private def prefixMatchLength(owner: String, fqn: String): Int = {
    val ownerParts = owner.split('.')
    val fqnParts = fqn.split('.')

    var matchLen = 0
    var i = 0
    while (i < ownerParts.length && i < fqnParts.length - 1) {
      if (ownerParts(i) == fqnParts(i)) {
        matchLen += ownerParts(i).length + 1 // +1 for the dot
        i += 1
      } else {
        return matchLen
      }
    }
    matchLen
  }

  private def normalizeOwner(owner: String): String = {
    owner
      .stripPrefix("_root_.")
      .stripSuffix(".")
      .stripSuffix("$package")
      .stripSuffix(".type")
  }

  private def addToGroupedImport(
      groupedImport: ScalaImportLine,
      name: String
  ): Option[l.TextEdit] = {
    // Check if the name is already in the group
    if (groupedImport.groupedNames.contains(name)) {
      return None // Already imported
    }

    // Find the right position to insert within the group (alphabetically)
    val insertionIndex = groupedImport.groupedNames.indexWhere(_ > name)
    val insertAt =
      if (insertionIndex == -1) groupedImport.groupedNames.length
      else insertionIndex

    if (groupedImport.isMultiline) {
      // Multiline grouped import - insert on its own line
      addToMultilineGroup(groupedImport, name, insertAt)
    } else {
      // Single-line grouped import
      addToSingleLineGroup(groupedImport, name, insertAt)
    }
  }

  private def addToSingleLineGroup(
      groupedImport: ScalaImportLine,
      name: String,
      insertAt: Int
  ): Option[l.TextEdit] = {
    if (insertAt == 0) {
      // Insert at the beginning of the group, after the opening brace
      val braceOffset = groupedImport.groupStartOffset
      Some(
        new l.TextEdit(
          offsetToRange(braceOffset),
          s"$name, "
        )
      )
    } else {
      // Insert after the previous element
      val prevName = groupedImport.groupedNames(insertAt - 1)
      val prevNameEnd = findNameEndInGroup(groupedImport, prevName)
      Some(
        new l.TextEdit(
          offsetToRange(prevNameEnd),
          s", $name"
        )
      )
    }
  }

  private def addToMultilineGroup(
      groupedImport: ScalaImportLine,
      name: String,
      insertAt: Int
  ): Option[l.TextEdit] = {
    // For multiline groups, we insert on a new line with proper indentation
    val groupIndent = groupedImport.memberIndent.getOrElse(indent + "  ")

    if (insertAt == 0) {
      // Insert at the beginning, after the opening brace
      val braceOffset = groupedImport.groupStartOffset
      Some(
        new l.TextEdit(
          offsetToRange(braceOffset),
          s"\n$groupIndent$name,"
        )
      )
    } else if (insertAt >= groupedImport.groupedNames.length) {
      // Insert at the end, before the closing brace
      val lastNameEnd = findNameEndInGroup(
        groupedImport,
        groupedImport.groupedNames.last
      )
      Some(
        new l.TextEdit(
          offsetToRange(lastNameEnd),
          s",\n$groupIndent$name"
        )
      )
    } else {
      // Insert in the middle, after the previous element
      val prevName = groupedImport.groupedNames(insertAt - 1)
      val prevNameEnd = findNameEndInGroup(groupedImport, prevName)
      Some(
        new l.TextEdit(
          offsetToRange(prevNameEnd),
          s",\n$groupIndent$name"
        )
      )
    }
  }

  private def findNameEndInGroup(
      groupedImport: ScalaImportLine,
      name: String
  ): Int = {
    // Find where the name ends in the grouped import
    // We need to find the exact name, not a substring (e.g., "Buffer" inside "ArrayBuffer")
    val groupContent = text.substring(
      groupedImport.groupStartOffset,
      groupedImport.importEndOffset
    )

    // Search for the name with word boundary consideration
    var searchStart = 0
    while (searchStart < groupContent.length) {
      val idx = groupContent.indexOf(name, searchStart)
      if (idx == -1) {
        return groupedImport.groupStartOffset
      }

      // Check if this is a standalone name (not part of a larger identifier)
      val beforeOk = idx == 0 || !groupContent.charAt(idx - 1).isLetterOrDigit
      val afterIdx = idx + name.length
      val afterOk =
        afterIdx >= groupContent.length || !groupContent
          .charAt(afterIdx)
          .isLetterOrDigit

      if (beforeOk && afterOk) {
        return groupedImport.groupStartOffset + idx + name.length
      }

      searchStart = idx + 1
    }

    groupedImport.groupStartOffset
  }

  private def createEditAtOffset(
      offset: Int,
      fqn: String,
      insertBefore: Boolean
  ): l.TextEdit = {
    val (editOffset, insertText) =
      if (insertBefore) {
        (offset, s"${indent}import $fqn\n")
      } else {
        (offset, s"\n${indent}import $fqn")
      }
    new l.TextEdit(offsetToRange(editOffset), insertText)
  }

  private def offsetToRange(offset: Int): l.Range = {
    val pos = offsetToPosition(offset)
    new l.Range(pos, pos)
  }

  private def offsetToPosition(offset: Int): l.Position = {
    var line = 0
    var col = 0
    var i = 0
    while (i < offset && i < text.length) {
      if (text.charAt(i) == '\n') {
        line += 1
        col = 0
      } else {
        col += 1
      }
      i += 1
    }
    new l.Position(line, col)
  }
}

/**
 * Represents an import statement in a Scala source file.
 * Supports both simple imports like `import scala.io.Source`
 * and grouped imports like `import scala.collection.{Map, List}`,
 * including multiline grouped imports.
 *
 * @param line The first line of the import (for display/debugging)
 * @param lineNumber The line number where the import starts
 * @param lineStartOffset The offset where the import line starts
 * @param importEndOffset The offset where the import ends (after closing brace for grouped)
 * @param owner The package/object that owns the imported symbols
 * @param isGrouped Whether this is a grouped import
 * @param isMultiline Whether the grouped import spans multiple lines
 * @param groupedNames The names imported in a grouped import
 * @param groupStartOffset Offset right after the opening brace
 * @param memberIndent The indentation used for members in multiline groups
 */
case class ScalaImportLine(
    line: String,
    lineNumber: Int,
    lineStartOffset: Int,
    importEndOffset: Int,
    owner: String,
    isGrouped: Boolean,
    isMultiline: Boolean,
    groupedNames: Seq[String],
    groupStartOffset: Int,
    memberIndent: Option[String]
) {}

object ScalaImportLine {

  def fromText(
      text: String,
      startOffset: Int,
      endOffset: Int
  ): Seq[ScalaImportLine] = {
    val result = Seq.newBuilder[ScalaImportLine]
    var i = startOffset

    while (i < endOffset) {
      // Skip whitespace and find next "import"
      while (i < endOffset && text.charAt(i).isWhitespace) {
        i += 1
      }

      if (i < endOffset - 7 && text.substring(i, i + 7) == "import ") {
        parseImportAt(text, i, endOffset) match {
          case Some(importLine) =>
            result += importLine
            i = importLine.importEndOffset
          case None =>
            // Skip to end of line if parsing failed
            while (i < endOffset && text.charAt(i) != '\n') {
              i += 1
            }
        }
      } else {
        // Skip to next line
        while (i < endOffset && text.charAt(i) != '\n') {
          i += 1
        }
        if (i < endOffset) i += 1
      }
    }

    result.result()
  }

  private def parseImportAt(
      text: String,
      importStart: Int,
      maxEnd: Int
  ): Option[ScalaImportLine] = {
    val lineNumber = text.substring(0, importStart).count(_ == '\n')
    val lineStart = text.lastIndexOf('\n', importStart) + 1

    // Find the "import " keyword and content after it
    val contentStart = importStart + 7 // "import ".length

    // Check if it's a grouped import by finding '{'
    val braceStart = findBraceStart(text, contentStart, maxEnd)

    if (braceStart >= 0) {
      // Grouped import - find the matching closing brace
      val braceEnd = findMatchingBrace(text, braceStart, maxEnd)
      if (braceEnd < 0) return None

      val owner = text.substring(contentStart, braceStart).trim.stripSuffix(".")
      val groupContent = text.substring(braceStart + 1, braceEnd)
      val isMultiline = groupContent.contains('\n')

      val names = groupContent
        .split(',')
        .map(_.trim)
        .filter(_.nonEmpty)
        .map { n =>
          // Handle renames: A => B -> take A
          val arrowIdx = n.indexOf("=>")
          if (arrowIdx > 0) n.substring(0, arrowIdx).trim
          else n.trim
        }
        .toSeq

      // For multiline groups, detect the indentation used
      val memberIndent =
        if (isMultiline) {
          // Find the indentation of the first member
          val firstNewline = groupContent.indexOf('\n')
          if (firstNewline >= 0) {
            val afterNewline = groupContent.substring(firstNewline + 1)
            val indent = afterNewline.takeWhile(_.isWhitespace)
            Some(indent)
          } else None
        } else None

      // Find the end of the line containing the closing brace
      var importEnd = braceEnd + 1
      while (
        importEnd < maxEnd && text.charAt(importEnd) != '\n' &&
        text.charAt(importEnd).isWhitespace
      ) {
        importEnd += 1
      }

      Some(
        ScalaImportLine(
          line = text.substring(importStart, math.min(importEnd, maxEnd)),
          lineNumber = lineNumber,
          lineStartOffset = lineStart,
          importEndOffset = importEnd,
          owner = owner,
          isGrouped = true,
          isMultiline = isMultiline,
          groupedNames = names,
          groupStartOffset = braceStart + 1,
          memberIndent = memberIndent
        )
      )
    } else {
      // Simple import - find end of line
      var lineEnd = contentStart
      while (lineEnd < maxEnd && text.charAt(lineEnd) != '\n') {
        lineEnd += 1
      }

      val importContent = text.substring(contentStart, lineEnd).trim
      val lastDot = importContent.lastIndexOf('.')
      if (lastDot <= 0) return None

      val owner = importContent.substring(0, lastDot)

      Some(
        ScalaImportLine(
          line = text.substring(importStart, lineEnd),
          lineNumber = lineNumber,
          lineStartOffset = lineStart,
          importEndOffset = lineEnd,
          owner = owner,
          isGrouped = false,
          isMultiline = false,
          groupedNames = Seq.empty,
          groupStartOffset = -1,
          memberIndent = None
        )
      )
    }
  }

  private def findBraceStart(text: String, from: Int, maxEnd: Int): Int = {
    var i = from
    while (i < maxEnd) {
      val c = text.charAt(i)
      if (c == '{') return i
      if (c == '\n') return -1 // No brace before end of line = simple import
      i += 1
    }
    -1
  }

  private def findMatchingBrace(
      text: String,
      braceStart: Int,
      maxEnd: Int
  ): Int = {
    var depth = 1
    var i = braceStart + 1
    while (i < maxEnd && depth > 0) {
      text.charAt(i) match {
        case '{' => depth += 1
        case '}' => depth -= 1
        case _ =>
      }
      i += 1
    }
    if (depth == 0) i - 1 else -1
  }
}
