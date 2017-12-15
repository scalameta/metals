package scala.meta.languageserver.compiler

import scala.meta.languageserver.Uri

case class Cursor(uri: Uri, contents: String, offset: Int)
