package scala.meta.internal.mtags

class EncoderDecoder(escapeChar: Char, unsafeChars: Set[Char]) {

  protected val toEscape: Map[Char, String] =
    unsafeChars
      .map(char => char -> (escapeChar + char.toHexString))
      .toMap

  protected val toDecode: Map[String, Char] =
    toEscape.map { case (k, v) => v -> k }

  def encode(args: String): String = {
    args.flatMap { char =>
      toEscape.getOrElse(char, char.toString())
    }
  }

  def decode(args: String): String = {
    val it = args.iterator
    var ch: Char = 'a'
    val buffer = new StringBuilder()
    while (it.hasNext) {
      ch = it.next()
      if (ch == escapeChar) {
        if (it.hasNext) {
          val first = it.next()
          if (it.hasNext) {
            val second = it.next()
            val value = s"$escapeChar$first$second"
            buffer.append(toDecode.getOrElse(value.toLowerCase(), value))
          } else {
            buffer.append(escapeChar.toString())
            buffer.append(first)
          }
        } else {
          buffer.append(ch)
        }

      } else {
        buffer.append(ch)
      }
    }
    buffer.toString()
  }
}
