package scala.meta.internal.mtags

object URIEncoderDecoder {

  // Some encoding schemes encode `:` (although not the first one as that indicates scheme).
  // Currently Metals doesn't encode `:` but does decode it
  private val toEscape: Map[Char, String] =
    Set('"', '<', '>', '&', '\'', '[', ']', '{', '}', ' ', '+', '!')
      .map(char => char -> ("%" + char.toInt.toHexString))
      .toMap

  private val toDecode: Map[String, Char] = toEscape.map { case (k, v) =>
    v -> k
  } + ("%3a" -> ':')

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
      if (ch == '%') {
        if (it.hasNext) {
          val first = it.next()
          if (it.hasNext) {
            val second = it.next()
            val value = s"%$first$second"
            buffer.append(toDecode.getOrElse(value.toLowerCase(), value))
          } else {
            buffer.append("%")
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
