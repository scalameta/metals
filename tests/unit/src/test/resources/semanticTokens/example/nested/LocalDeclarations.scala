<<package>>/*keyword*/ <<example>>/*namespace*/.<<nested>>/*namespace*/

<<trait>>/*keyword*/ <<LocalDeclarations>>/*interface,abstract*/ {
  <<def>>/*keyword*/ <<foo>>/*method,abstract*/(): <<Unit>>/*class,abstract*/
}

<<trait>>/*keyword*/ <<Foo>>/*interface,abstract*/ {
  <<val>>/*keyword*/ <<y>>/*variable,readonly*/ = <<3>>/*number*/
}

<<object>>/*keyword*/ <<LocalDeclarations>>/*class*/ {
  <<def>>/*keyword*/ <<create>>/*method*/(): <<LocalDeclarations>>/*interface,abstract*/ = {
    <<def>>/*keyword*/ <<bar>>/*method*/(): <<Unit>>/*class,abstract*/ = ()

    <<val>>/*keyword*/ <<x>>/*variable,readonly*/ = <<new>>/*keyword*/ {
      <<val>>/*keyword*/ <<x>>/*variable,readonly*/ = <<2>>/*number*/
    }

    <<val>>/*keyword*/ <<y>>/*variable,readonly*/ = <<new>>/*keyword*/ <<Foo>>/*interface,abstract*/ {}

    <<x>>/*variable,readonly*/.<<x>>/*variable,readonly*/ <<+>>/*method,abstract*/ <<y>>/*variable,readonly*/.<<y>>/*variable,readonly*/

    <<new>>/*keyword*/ <<LocalDeclarations>>/*interface,abstract*/ <<with>>/*keyword*/ <<Foo>>/*interface,abstract*/ {
      <<override>>/*modifier*/ <<def>>/*keyword*/ <<foo>>/*method*/(): <<Unit>>/*class,abstract*/ = <<bar>>/*method*/()
    }

  }
}