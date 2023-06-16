<<package>>/*keyword*/ <<example>>/*namespace*/.<<nested>>/*namespace*/

<<trait>>/*keyword*/ <<LocalDeclarations>>/*interface,abstract*/:
  <<def>>/*keyword*/ <<foo>>/*method,declaration*/(): <<Unit>>/*class,abstract*/

<<trait>>/*keyword*/ <<Foo>>/*interface,abstract*/:
  <<val>>/*keyword*/ <<y>>/*variable,definition,readonly*/ = <<3>>/*number*/

<<object>>/*keyword*/ <<LocalDeclarations>>/*class*/:
  <<def>>/*keyword*/ <<create>>/*method,definition*/(): <<LocalDeclarations>>/*interface,abstract*/ =
    <<def>>/*keyword*/ <<bar>>/*method,definition*/(): <<Unit>>/*class,abstract*/ = ()

    <<val>>/*keyword*/ <<x>>/*variable,definition,readonly*/ = <<new>>/*keyword*/:
      <<val>>/*keyword*/ <<x>>/*variable,definition,readonly*/ = <<2>>/*number*/

    <<val>>/*keyword*/ <<y>>/*variable,definition,readonly*/ = <<new>>/*keyword*/ <<Foo>>/*interface,abstract*/ {}

    <<val>>/*keyword*/ <<yy>>/*variable,definition,readonly*/ = <<y>>/*variable,readonly*/.<<y>>/*variable,readonly*/

    <<new>>/*keyword*/ <<LocalDeclarations>>/*interface,abstract*/ <<with>>/*keyword*/ <<Foo>>/*interface,abstract*/:
      <<override>>/*modifier*/ <<def>>/*keyword*/ <<foo>>/*method,definition*/(): <<Unit>>/*class,abstract*/ = <<bar>>/*method*/()