<<package>>/*keyword*/ <<example>>/*namespace*/

<<class>>/*keyword*/ <<Miscellaneous>>/*class*/ {
  <<// backtick identifier>>/*comment*/
  <<val>>/*keyword*/ <<`a b`>>/*variable,definition,readonly*/ = <<42>>/*number*/

  <<// block with only wildcard value>>/*comment*/
  <<def>>/*keyword*/ <<apply>>/*method,definition*/(): <<Unit>>/*class,abstract*/ = {
    <<val>>/*keyword*/ <<_>>/*variable,readonly*/ = <<42>>/*number*/
  }
  <<// infix + inferred apply/implicits/tparams>>/*comment*/
  (<<List>>/*class*/(<<1>>/*number*/)
    .<<map>>/*method*/(<<_>>/*variable,readonly*/ <<+>>/*method*/ <<1>>/*number*/)
    <<++>>/*method*/
      <<List>>/*class*/(<<3>>/*number*/))
}