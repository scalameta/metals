<<package>>/*keyword*/ <<example>>/*namespace*/

<<class>>/*keyword*/ <<ImplicitConversions>>/*class*/ {
  <<implicit>>/*modifier*/ <<def>>/*keyword*/ <<string2Number>>/*method*/(
      <<string>>/*parameter,readonly*/: <<String>>/*type*/
  ): <<Int>>/*class,abstract*/ = <<42>>/*number*/
  <<val>>/*keyword*/ <<message>>/*variable,readonly*/ = <<"">>/*string*/
  <<val>>/*keyword*/ <<number>>/*variable,readonly*/ = <<42>>/*number*/
  <<val>>/*keyword*/ <<tuple>>/*variable,readonly*/ = (<<1>>/*number*/, <<2>>/*number*/)
  <<val>>/*keyword*/ <<char>>/*variable,readonly*/: <<Char>>/*class,abstract*/ = <<'a'>>/*string*/

  <<// extension methods>>/*comment*/
  <<message>>/*variable,readonly*/
    .<<stripSuffix>>/*method*/(<<"h">>/*string*/)
  <<tuple>>/*variable,readonly*/ <<+>>/*method*/ <<"Hello">>/*string*/

  <<// implicit conversions>>/*comment*/
  <<val>>/*keyword*/ <<x>>/*variable,readonly*/: <<Int>>/*class,abstract*/ = <<message>>/*variable,readonly*/

  <<// interpolators>>/*comment*/
  <<s>>/*keyword*/<<">>/*string*/<<Hello >>/*string*/<<$>>/*keyword*/<<message>>/*variable,readonly*/<< >>/*string*/<<$>>/*keyword*/<<number>>/*variable,readonly*/<<">>/*string*/
  <<s>>/*keyword*/<<""">>/*string*/<<Hello>>/*string*/
<<     |>>/*string*/<<$>>/*keyword*/<<message>>/*variable,readonly*/<<>>/*string*/
<<     |>>/*string*/<<$>>/*keyword*/<<number>>/*variable,readonly*/<<""">>/*string*/.<<stripMargin>>/*method*/

  <<val>>/*keyword*/ <<a>>/*variable,readonly*/: <<Int>>/*class,abstract*/ = <<char>>/*variable,readonly*/
  <<val>>/*keyword*/ <<b>>/*variable,readonly*/: <<Long>>/*class,abstract*/ = <<char>>/*variable,readonly*/
}