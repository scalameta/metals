package scala.meta.internal.decorations

import javax.annotation.Nullable

case class ThemableDecorationInstanceRenderOptions(
    @Nullable before: ThemableDecorationAttachmentRenderOptions = null,
    @Nullable after: ThemableDecorationAttachmentRenderOptions = null,
    @Nullable light: ThemableDecorationInstanceRenderOptions = null,
    @Nullable dark: ThemableDecorationInstanceRenderOptions = null
)
