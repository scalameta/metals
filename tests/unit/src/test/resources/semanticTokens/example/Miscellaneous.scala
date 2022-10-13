<<package>>/*keyword*/ <<example>>/*namespace*/

<<class>>/*keyword*/ <<Miscellaneous>>/*class*/ {
  <<// backtick identifier>>/*comment*/
  <<val>>/*keyword*/ <<`a b`>>/*variable, readonly*/ = <<42>>/*number*/

  <<// block with only wildcard value>>/*comment*/
  <<def>>/*keyword*/ <<apply>>/*method*/(): <<Unit>>/*class,abstract*/ = {
    <<val>>/*keyword*/ <<_>>/*variable*/ = <<42>>/*number*/
  }
  <<// infix + inferred apply/implicits/tparams>>/*comment*/
  (<<List>>/*variable,readonly*/(<<1>>/*number*/)
    .<<map>>/*method*/(<<_>>/*variable*/ <<+>>/*method,abstract*/ <<1>>/*number*/)
    <<++>>/*method*/
      <<List>>/*variable,readonly*/(<<3>>/*number*/))
}