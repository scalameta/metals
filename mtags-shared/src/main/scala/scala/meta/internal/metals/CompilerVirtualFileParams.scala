package scala.meta.internal.metals

import java.net.URI
import java.util.Optional

import scala.collection.mutable

import scala.meta.pc.CancelToken
import scala.meta.pc.OutlineFiles
import scala.meta.pc.VirtualFileParams

case class CompilerVirtualFileParams(
    uri: URI,
    text: String,
    token: CancelToken,
    override val outlineFiles: Optional[OutlineFiles]
) extends VirtualFileParams {

  // The code below is copied from scala.meta InternalInput. Having the
  // offset/line mapping in VirtualFileParams (and mtags-shared) simplifies a
  // lot of code.
  private[this] lazy val chars = text.toCharArray

  private lazy val cachedLineIndices: Array[Int] = {
    val chars = this.chars
    val buf = new mutable.ArrayBuffer[Int]
    buf += 0
    var i = 0
    var lastIsCR = false
    while (i < chars.length) {
      // we consider all `\n`, `\r\n` and `\r` as new line
      if (chars(i) == '\n') buf += (i + 1) else if (lastIsCR) buf += i
      lastIsCR = chars(i) == '\r'
      i += 1
    }
    if (buf.last != chars.length)
      buf += chars.length // sentinel value used for binary search
    buf.toArray
  }

  override def lineToOffset(line: Int): Int = {
    // NOTE: The length-1 part is not a typo, it's to accommodate the sentinel value.
    if (!(0 <= line && line <= cachedLineIndices.length - 1)) {
      val message =
        s"$line is not a valid line number, allowed [0..${cachedLineIndices.length - 1}]"
      throw new IllegalArgumentException(message)
    }
    cachedLineIndices(line)
  }

  override def offsetToLine(offset: Int): Int = {
    val chars = this.chars
    val a = cachedLineIndices
    // NOTE: We allow chars.length, because it's a valid value for an offset.
    if (!(0 <= offset && offset <= chars.length)) {
      val message =
        s"$offset is not a valid offset, allowed [0..${chars.length}]"
      throw new IllegalArgumentException(message)
    }
    // NOTE: chars.length requires a really ugly special case.
    // If the file doesn't end with \n, then it's simply last_line:last_col+1.
    // But if the file does end with \n, then it's last_line+1:0.
    if (
      offset == chars.length &&
      (0 < chars.length && CompilerVirtualFileParams.newLine(chars(offset - 1)))
    )
      return a.length - 1
    var lo = 0
    var hi = a.length - 1
    while (hi - lo > 1) {
      val mid = (hi + lo) / 2
      if (offset < a(mid)) hi = mid
      else if (a(mid) == offset) return mid
      else /* if (a(mid) < offset */ lo = mid
    }
    lo
  }
}

object CompilerVirtualFileParams {
  private val newLine = Set('\n', '\r')
  def apply(
      uri: URI,
      text: String,
      token: CancelToken = EmptyCancelToken
  ): CompilerVirtualFileParams =
    CompilerVirtualFileParams(uri, text, token, Optional.empty())
}
