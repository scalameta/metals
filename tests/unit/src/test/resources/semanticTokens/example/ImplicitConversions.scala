<<package>>/*keyword*/ <<example>>/*namespace*/

<<class>>/*keyword*/ <<ImplicitConversions>>/*class*/ {
  <<implicit>>/*modifier*/ <<def>>/*keyword*/ <<string2Number>>/*method,definition*/(
      <<string>>/*parameter,declaration,readonly*/: <<String>>/*type*/
  ): <<Int>>/*class,abstract*/ = <<42>>/*number*/
  <<val>>/*keyword*/ <<message>>/*variable,definition,readonly*/ = <<"">>/*string*/
  <<val>>/*keyword*/ <<number>>/*variable,definition,readonly*/ = <<42>>/*number*/
  <<val>>/*keyword*/ <<tuple>>/*variable,definition,readonly*/ = (<<1>>/*number*/, <<2>>/*number*/)
  <<val>>/*keyword*/ <<char>>/*variable,definition,readonly*/: <<Char>>/*class,abstract*/ = <<'a'>>/*string*/

  <<// extension methods>>/*comment*/
  <<message>>/*variable,readonly*/
    .<<stripSuffix>>/*method*/(<<"h">>/*string*/)
  <<tuple>>/*variable,readonly*/ <<+>>/*method*/ <<"Hello">>/*string*/

  <<// implicit conversions>>/*comment*/
  <<val>>/*keyword*/ <<x>>/*variable,definition,readonly*/: <<Int>>/*class,abstract*/ = <<message>>/*variable,readonly*/

  <<// interpolators>>/*comment*/
  <<s>>/*keyword*/<<">>/*string*/<<Hello >>/*string*/<<$>>/*keyword*/<<message>>/*variable,readonly*/<< >>/*string*/<<$>>/*keyword*/<<number>>/*variable,readonly*/<<">>/*string*/
  <<s>>/*keyword*/<<""">>/*string*/<<Hello>>/*string*/
<<     |>>/*string*/<<$>>/*keyword*/<<message>>/*variable,readonly*/
<<     |>>/*string*/<<$>>/*keyword*/<<number>>/*variable,readonly*/<<""">>/*string*/.<<stripMargin>>/*method*/

  <<val>>/*keyword*/ <<a>>/*variable,definition,readonly*/: <<Int>>/*class,abstract*/ = <<char>>/*variable,readonly*/
  <<val>>/*keyword*/ <<b>>/*variable,definition,readonly*/: <<Long>>/*class,abstract*/ = <<char>>/*variable,readonly*/
}