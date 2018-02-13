package scala.meta.metals.compiler

import scala.meta.metals.Uri

case class Cursor(uri: Uri, contents: String, offset: Int)
