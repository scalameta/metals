package tests

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer

import scala.meta.internal.metals.TextEdits
import scala.meta.internal.pc.SemanticTokenCapability._

import org.eclipse.{lsp4j => l}

object TestSemanticTokens {

  def semanticString(fileContent: String, obtainedTokens: List[Int]) = {

    /**
     * construct string from token type and mods to decorate codes.
     */
    def decorationString(typeInd: Int, modInd: Int): String = {
      val buffer = ListBuffer.empty[String]

      // TokenType
      if (typeInd != -1) {
        buffer.addAll(List(TokenTypes(typeInd)))
      }

      // TokenModifier
      // wkList = (e.g.) modInd=32 -> 100000 -> "000001"
      val wkList = modInd.toBinaryString.toCharArray().toList.reverse
      for (i: Int <- 0 to wkList.size - 1) {
        if (wkList(i).toString == "1") {
          buffer.addAll(
            List(
              TokenModifiers(i)
            )
          )
        }
      }

      // return
      buffer.toList.mkString(",")
    }

    val allTokens = obtainedTokens
      .grouped(5)
      .map(_.toList)
      .map {
        case List(
              deltaLine,
              deltaStartChar,
              length,
              tokenType,
              tokenModifier,
            ) => // modifiers ignored for now
          (
            new l.Position(deltaLine, deltaStartChar),
            length,
            decorationString(tokenType, tokenModifier),
          )
        case _ =>
          throw new RuntimeException("Expected output dividable by 5")
      }
      .toList

    @tailrec
    def toAbsolutePositions(
        positions: List[(l.Position, Int, String)],
        last: l.Position,
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
