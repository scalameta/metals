package scala.meta.internal.decorations

import javax.annotation.Nullable

case class ThemableDecorationAttachmentRenderOptions(
    @Nullable contentText: String = null,
    @Nullable contentIconPath: String = null,
    @Nullable border: String = null,
    @Nullable borderColor: String = null,
    @Nullable fontStyle: String = null,
    @Nullable fontWeight: String = null,
    @Nullable textDecoration: String = null,
    @Nullable color: String = null,
    @Nullable backgroundColor: String = null,
    @Nullable margin: String = null,
    @Nullable width: String = null,
    @Nullable height: String = null,
    @Nullable opacity: java.lang.Double = null,
    @Nullable light: ThemableDecorationAttachmentRenderOptions = null,
    @Nullable dark: ThemableDecorationAttachmentRenderOptions = null
)
