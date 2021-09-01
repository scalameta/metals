package scala.meta.internal.metals

case class FindTextInDependencyJarsRequest(
    mask: String,
    content: String
)
