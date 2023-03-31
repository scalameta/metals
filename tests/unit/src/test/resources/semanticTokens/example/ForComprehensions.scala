<<package>>/*keyword*/ <<example>>/*namespace*/

<<class>>/*keyword*/ <<ForComprehensions>>/*class*/ {
  <<for>>/*keyword*/ {
    <<a>>/*parameter,declaration,readonly*/ <<<->>/*operator*/ <<List>>/*class*/(<<1>>/*number*/)
    <<b>>/*parameter,declaration,readonly*/ <<<->>/*operator*/ <<List>>/*class*/(<<a>>/*parameter,readonly*/)
    <<if>>/*keyword*/ (
      <<a>>/*parameter,readonly*/,
      <<b>>/*parameter,readonly*/,
    ) <<==>>/*method*/ (<<1>>/*number*/, <<2>>/*number*/)
    (
      <<c>>/*variable,definition,readonly*/,
      <<d>>/*variable,definition,readonly*/,
    ) <<<->>/*operator*/ <<List>>/*class*/((<<a>>/*parameter,readonly*/, <<b>>/*parameter,readonly*/))
    <<if>>/*keyword*/ (
      <<a>>/*parameter,readonly*/,
      <<b>>/*parameter,readonly*/,
      <<c>>/*variable,readonly*/,
      <<d>>/*variable,readonly*/,
    ) <<==>>/*method*/ (<<1>>/*number*/, <<2>>/*number*/, <<3>>/*number*/, <<4>>/*number*/)
    <<e>>/*variable,definition,readonly*/ = (
      <<a>>/*parameter,readonly*/,
      <<b>>/*parameter,readonly*/,
      <<c>>/*variable,readonly*/,
      <<d>>/*variable,readonly*/,
    )
    <<if>>/*keyword*/ <<e>>/*variable,readonly*/ <<==>>/*method*/ (<<1>>/*number*/, <<2>>/*number*/, <<3>>/*number*/, <<4>>/*number*/)
    <<f>>/*parameter,declaration,readonly*/ <<<->>/*operator*/ <<List>>/*class*/(<<e>>/*variable,readonly*/)
  } <<yield>>/*keyword*/ {
    (
      <<a>>/*parameter,readonly*/,
      <<b>>/*parameter,readonly*/,
      <<c>>/*variable,readonly*/,
      <<d>>/*variable,readonly*/,
      <<e>>/*variable,readonly*/,
      <<f>>/*parameter,readonly*/,
    )
  }

}