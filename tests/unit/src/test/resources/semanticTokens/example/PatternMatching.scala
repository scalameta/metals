<<package>>/*keyword*/ <<example>>/*namespace*/

<<class>>/*keyword*/ <<PatternMatching>>/*class*/ {
  <<val>>/*keyword*/ <<some>>/*variable,readonly*/ = <<Some>>/*class*/(<<1>>/*number*/)
  <<some>>/*variable,readonly*/ <<match>>/*keyword*/ {
    <<case>>/*keyword*/ <<Some>>/*class*/(number) <<=>>>/*operator*/
      <<number>>/*variable,readonly*/
  }

  <<// tuple deconstruction>>/*comment*/
  <<val>>/*keyword*/ (<<left>>/*variable,readonly*/, <<right>>/*variable,readonly*/) = (<<1>>/*number*/, <<2>>/*number*/)
  (<<left>>/*variable,readonly*/, <<right>>/*variable,readonly*/)

  <<// val deconstruction>>/*comment*/
  <<val>>/*keyword*/ <<Some>>/*class*/(number1) =
    <<some>>/*variable,readonly*/
  <<println>>/*method*/(<<number1>>/*variable,readonly*/)

  <<def>>/*keyword*/ <<localDeconstruction>>/*method*/ = {
    <<val>>/*keyword*/ <<Some>>/*class*/(<<number2>>/*variable, readonly*/) =
      <<some>>/*variable,readonly*/
    <<number2>>/*variable,readonly*/
  }
}