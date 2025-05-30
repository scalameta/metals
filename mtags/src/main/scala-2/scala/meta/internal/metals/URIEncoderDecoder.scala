package scala.meta.internal.mtags

object URIEncoderDecoder
    extends EncoderDecoder(
      '%',
      Set('"', '<', '>', '&', '\'', '[', ']', '{', '}', ' ', '+')
    ) {
  override protected val toDecode: Map[String, Char] = toEscape.map {
    case (k, v) => v -> k
  } + ("%3a" -> ':')
}
