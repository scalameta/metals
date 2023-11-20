<<package>>/*keyword*/ <<example>>/*namespace*/

<<given>>/*keyword*/ <<intValue>>/*variable,definition,readonly*/: <<Int>>/*class,abstract*/ = <<4>>/*number*/
<<given>>/*keyword*/ <<String>>/*type*/ = <<"str">>/*string*/
<<given>>/*keyword*/ (<<using>>/*keyword*/ <<i>>/*parameter,declaration,readonly*/: <<Int>>/*class,abstract*/): <<Double>>/*class,abstract*/ = <<4.0>>/*number*/
<<given>>/*keyword*/ [<<T>>/*typeParameter,definition,abstract*/]: <<List>>/*type*/[<<T>>/*typeParameter,abstract*/] = <<Nil>>/*class*/
<<given>>/*keyword*/ <<given_Char>>/*variable,definition,readonly*/: <<Char>>/*class,abstract*/ = <<'?'>>/*string*/
<<given>>/*keyword*/ <<`given_Float`>>/*variable,definition,readonly*/: <<Float>>/*class,abstract*/ = <<3.0>>/*number*/
<<given>>/*keyword*/ <<`* *`>>/*variable,definition,readonly*/ : <<Long>>/*class,abstract*/ = <<5>>/*number*/

<<def>>/*keyword*/ <<method>>/*method,definition*/(<<using>>/*keyword*/ <<Int>>/*class,abstract*/) = <<"">>/*string*/

<<object>>/*keyword*/ <<X>>/*class*/:
  <<given>>/*keyword*/ <<Double>>/*class,abstract*/ = <<4.0>>/*number*/
  <<val>>/*keyword*/ <<double>>/*variable,definition,readonly*/ = <<given_Double>>/*variable,readonly*/

  <<given>>/*keyword*/ <<of>>/*method,definition*/[<<A>>/*typeParameter,definition,abstract*/]: <<Option>>/*class,abstract*/[<<A>>/*typeParameter,abstract*/] = <<???>>/*method*/

<<trait>>/*keyword*/ <<Xg>>/*interface,abstract*/:
  <<def>>/*keyword*/ <<doX>>/*method,declaration*/: <<Int>>/*class,abstract*/

<<trait>>/*keyword*/ <<Yg>>/*interface,abstract*/:
  <<def>>/*keyword*/ <<doY>>/*method,declaration*/: <<String>>/*type*/

<<trait>>/*keyword*/ <<Zg>>/*interface,abstract*/[<<T>>/*typeParameter,definition,abstract*/]:
  <<def>>/*keyword*/ <<doZ>>/*method,declaration*/: <<List>>/*type*/[<<T>>/*typeParameter,abstract*/]

<<given>>/*keyword*/ <<Xg>>/*interface,abstract*/ <<with>>/*keyword*/
  <<def>>/*keyword*/ <<doX>>/*method,definition*/ = <<7>>/*number*/

<<given>>/*keyword*/ (<<using>>/*keyword*/ <<Xg>>/*interface,abstract*/): <<Yg>>/*interface,abstract*/ <<with>>/*keyword*/
  <<def>>/*keyword*/ <<doY>>/*method,definition*/ = <<"7">>/*string*/

<<given>>/*keyword*/ [<<T>>/*typeParameter,definition,abstract*/]: <<Zg>>/*interface,abstract*/[<<T>>/*typeParameter,abstract*/] <<with>>/*keyword*/
  <<def>>/*keyword*/ <<doZ>>/*method,definition*/: <<List>>/*type*/[<<T>>/*typeParameter,abstract*/] = <<Nil>>/*class*/

<<val>>/*keyword*/ <<a>>/*variable,definition,readonly*/ = <<intValue>>/*variable,readonly*/
<<val>>/*keyword*/ <<b>>/*variable,definition,readonly*/ = <<given_String>>/*variable,readonly*/
<<val>>/*keyword*/ <<c>>/*variable,definition,readonly*/ = <<X>>/*class*/.<<given_Double>>/*variable,readonly*/
<<val>>/*keyword*/ <<d>>/*variable,definition,readonly*/ = <<given_List_T>>/*method*/[<<Int>>/*class,abstract*/]
<<val>>/*keyword*/ <<e>>/*variable,definition,readonly*/ = <<given_Char>>/*variable,readonly*/
<<val>>/*keyword*/ <<f>>/*variable,definition,readonly*/ = <<given_Float>>/*variable,readonly*/
<<val>>/*keyword*/ <<g>>/*variable,definition,readonly*/ = <<`* *`>>/*variable,readonly*/
<<val>>/*keyword*/ <<i>>/*variable,definition,readonly*/ = <<X>>/*class*/.<<of>>/*method*/[<<Int>>/*class,abstract*/]
<<val>>/*keyword*/ <<x>>/*class,definition*/ = <<given_Xg>>/*class*/
<<val>>/*keyword*/ <<y>>/*variable,definition,readonly*/ = <<given_Yg>>/*method*/
<<val>>/*keyword*/ <<z>>/*variable,definition,readonly*/ = <<given_Zg_T>>/*method*/[<<String>>/*type*/]