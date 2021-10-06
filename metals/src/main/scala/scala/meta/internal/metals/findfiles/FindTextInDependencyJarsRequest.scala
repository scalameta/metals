package scala.meta.internal.metals.findfiles

import javax.annotation.Nullable

case class FindTextInDependencyJarsRequest(
    @Nullable
    options: FindTextInFilesOptions,
    query: TextSearchQuery
)

case class TextSearchQuery(
    pattern: String,
    @Nullable
    isRegExp: java.lang.Boolean,
    @Nullable
    isCaseSensitive: java.lang.Boolean,
    @Nullable
    isWordMatch: java.lang.Boolean
)

case class FindTextInFilesOptions(
    @Nullable
    include: String,
    @Nullable
    exclude: String
)
