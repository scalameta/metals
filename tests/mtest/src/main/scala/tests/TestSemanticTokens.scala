package tests

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.TextEdits
import scala.meta.internal.pc.SemanticTokens
import scala.meta.internal.pc.SemanticTokens._
import scala.meta.pc.Node
import scala.meta.pc.VirtualFileParams

import org.eclipse.{lsp4j => l}

object TestSemanticTokens {

  def removeSemanticHighlightDecorations(contents: String): String =
    contents
      .replaceAll(raw"/\*[\w,]+\*/", "")
      .replaceAll(raw"\<\<|\>\>", "")

  def decorationString(typeInd: Int, modInd: Int): String = {
    val buffer = ListBuffer.empty[String]

    // TokenType
    if (typeInd != -1) {
      buffer.++=(List(TokenTypes(typeInd)))
    }

    // TokenModifier
    // wkList = (e.g.) modInd=32 -> 100000 -> "000001"
    val wkList = modInd.toBinaryString.toCharArray().toList.reverse
    for (i: Int <- 0 to wkList.size - 1) {
      if (wkList(i).toString == "1") {
        buffer.++=(
          List(
            TokenModifiers(i)
          )
        )
      }
    }

    // return
    buffer.toList.mkString(",")
  }

  def semanticStringV2(
      params: VirtualFileParams,
      semanticTokens: l.SemanticTokens
  ): String = {
    val nodes = semanticTokens.getData().asScala
    if (nodes.length % 5 != 0) {
      throw new RuntimeException(
        s"Expected semantic tokens length to be dividable by 5. URI: ${params.uri}"
      )
    }
    val out = new StringBuilder
    val it = nodes.iterator.buffered
    var prev = new l.Position(0, 0)
    def peekLine() = prev.getLine() + it.head
    var start = 0
    params.text().linesWithSeparators.zipWithIndex.foreach {
      case (line, lineNumber) =>
        val end = start + line.length
        out.append(line.replace("\t", " "))
        while (it.hasNext && lineNumber == peekLine()) {
          val deltaLine = it.next()
          val deltaStart = it.next()
          val length = it.next()
          val tokenType = it.next()
          val tokenModifier = it.next()
          val startLine = prev.getLine() + deltaLine
          val startCharacter =
            deltaStart + (if (lineNumber == prev.getLine()) prev.getCharacter()
                          else 0)
          out.append(" ".repeat(startCharacter))
          out.append("^".repeat(length))
          out.append(" ")
          out.append(decorationString(tokenType, tokenModifier))
          out.append("\n")

          prev = new l.Position(startLine, startCharacter)
        }

        start = end
    }
    out.toString()
  }

  // We can't always correctly determin which node will be used outside the compiler,
  // because we don't know about tokens, and some nodes contain synthetic symbols.
  // Here we try to pick the same node as `SemanticTokenProvider` will.
  def pcSemanticString(fileContent: String, nodes: List[Node]): String = {
    val wkStr = new StringBuilder

    // Scalameta tokenizer will drop anything anyway that doesn't match an existing identifier
    def isIdentifier(start: Int, end: Int) =
      (fileContent.slice(start, end).matches("^[\\d\\w`+-_!@]+$"))

    def iter(nodes: List[Node], curr: Int): Int = {
      nodes match {
        case head :: rest
            if (curr <= head.start && head.start() != head
              .end()) && isIdentifier(head.start(), head.end()) =>
          val isValid = rest
            .takeWhile(node =>
              node.end() < head.end() || (node.end() == head.end() && node
                .start() > head.start())
            )
            .isEmpty
          if (isValid) {
            val candidates = head :: rest.takeWhile(nxt =>
              nxt.start() == head.start() && isIdentifier(
                nxt.start(),
                nxt.end()
              )
            )
            val node = candidates.maxBy(node =>
              SemanticTokens.getTypePriority(node.tokenType())
            )
            val slice = fileContent.slice(curr, node.start)
            wkStr ++= slice
            wkStr ++= "<<"
            wkStr ++= fileContent.slice(node.start, node.end)
            wkStr ++= ">>/*"
            wkStr ++= decorationString(node.tokenType, node.tokenModifier)
            wkStr ++= "*/"
            iter(rest, node.end())
          } else {
            iter(rest, curr)
          }
        case _ :: rest => iter(rest, curr)
        case Nil => curr
      }
    }
    val curr = iter(nodes, 0)
    wkStr ++= fileContent.slice(curr, fileContent.size)
    wkStr.mkString
  }
  def semanticString(fileContent: String, obtainedTokens: List[Int]): String = {

    /**
     * construct string from token type and mods to decorate codes.
     */

    val allTokens = obtainedTokens
      .grouped(5)
      .map(_.toList)
      .map {
        case List(
              deltaLine,
              deltaStartChar,
              length,
              tokenType,
              tokenModifier
            ) => // modifiers ignored for now
          (
            new l.Position(deltaLine, deltaStartChar),
            length,
            decorationString(tokenType, tokenModifier)
          )
        case _ =>
          throw new RuntimeException("Expected output dividable by 5")
      }
      .toList

    @tailrec
    def toAbsolutePositions(
        positions: List[(l.Position, Int, String)],
        last: l.Position
    ): Unit = {
      positions match {
        case (head, _, _) :: next =>
          if (head.getLine() != 0)
            head.setLine(last.getLine() + head.getLine())
          else {
            head.setLine(last.getLine())
            head.setCharacter(
              last.getCharacter() + head.getCharacter()
            )
          }
          toAbsolutePositions(next, head)
        case Nil =>
      }
    }
    toAbsolutePositions(allTokens, new l.Position(0, 0))

    // Build textEdits  e.g. which converts 'def'  to  '<<def>>/*keyword*/'
    val edits = allTokens.map { case (pos, len, typ) =>
      val startEdit = new l.TextEdit(new l.Range(pos, pos), "<<")
      val end = new l.Position(pos.getLine(), pos.getCharacter() + len)
      val endEdit = new l.TextEdit(new l.Range(end, end), s">>/*${typ}*/")
      List(startEdit, endEdit)
    }.flatten

    TextEdits.applyEdits(fileContent, edits)

  }
}
