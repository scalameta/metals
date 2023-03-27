<<package>>/*keyword*/ <<example>>/*namespace*/

<<given>>/*keyword*/ <<intValue>>/*variable,readonly*/: <<Int>>/*class,abstract*/ = <<4>>/*number*/
<<given>>/*keyword*/ <<String>>/*type*/ = <<"str">>/*string*/
<<given>>/*keyword*/ (<<using>>/*keyword*/ <<i>>/*parameter,readonly*/: <<Int>>/*class,abstract*/): <<Double>>/*class,abstract*/ = <<4.0>>/*number*/
<<given>>/*keyword*/ [<<T>>/*typeParameter,abstract*/]: <<List>>/*type*/[<<T>>/*typeParameter,abstract*/] = <<Nil>>/*variable,readonly*/
<<given>>/*keyword*/ <<given_Char>>/*variable,readonly*/: <<Char>>/*class,abstract*/ = <<'?'>>/*string*/
<<given>>/*keyword*/ <<`given_Float`>>/*variable,readonly*/: <<Float>>/*class,abstract*/ = <<3.0>>/*number*/
<<given>>/*keyword*/ <<`* *`>>/*variable,readonly*/ : <<Long>>/*class,abstract*/ = <<5>>/*number*/

<<def>>/*keyword*/ <<method>>/*method*/(<<using>>/*keyword*/ <<Int>>/*class,abstract*/) = <<"">>/*string*/

<<object>>/*keyword*/ <<X>>/*class*/:
  <<given>>/*keyword*/ <<Double>>/*class,abstract*/ = <<4.0>>/*number*/
  <<val>>/*keyword*/ <<double>>/*variable,readonly*/ = <<given_Double>>/*variable,readonly*/

  <<given>>/*keyword*/ <<of>>/*method*/[<<A>>/*typeParameter,abstract*/]: <<Option>>/*class,abstract*/[<<A>>/*typeParameter,abstract*/] = <<???>>/*method*/

<<trait>>/*keyword*/ <<Xg>>/*interface,abstract*/:
  <<def>>/*keyword*/ <<doX>>/*method*/: <<Int>>/*class,abstract*/

<<trait>>/*keyword*/ <<Yg>>/*interface,abstract*/:
  <<def>>/*keyword*/ <<doY>>/*method*/: <<String>>/*type*/

<<trait>>/*keyword*/ <<Zg>>/*interface,abstract*/[<<T>>/*typeParameter,abstract*/]:
  <<def>>/*keyword*/ <<doZ>>/*method*/: <<List>>/*type*/[<<T>>/*typeParameter,abstract*/]

<<given>>/*keyword*/ <<Xg>>/*interface,abstract*/ <<with>>/*keyword*/
  <<def>>/*keyword*/ <<doX>>/*method*/ = <<7>>/*number*/

<<given>>/*keyword*/ (<<using>>/*keyword*/ <<Xg>>/*interface,abstract*/): <<Yg>>/*interface,abstract*/ <<with>>/*keyword*/
  <<def>>/*keyword*/ <<doY>>/*method*/ = <<"7">>/*string*/

<<given>>/*keyword*/ [<<T>>/*typeParameter,abstract*/]: <<Zg>>/*interface,abstract*/[<<T>>/*typeParameter,abstract*/] <<with>>/*keyword*/
  <<def>>/*keyword*/ <<doZ>>/*method*/: <<List>>/*type*/[<<T>>/*typeParameter,abstract*/] = <<Nil>>/*variable,readonly*/

<<val>>/*keyword*/ <<a>>/*variable,readonly*/ = <<intValue>>/*variable,readonly*/
<<val>>/*keyword*/ <<b>>/*variable,readonly*/ = <<given_String>>/*variable,readonly*/
<<val>>/*keyword*/ <<c>>/*variable,readonly*/ = <<X>>/*class*/.<<given_Double>>/*variable,readonly*/
<<val>>/*keyword*/ <<d>>/*variable,readonly*/ = <<given_List_T>>/*method*/[<<Int>>/*class,abstract*/]
<<val>>/*keyword*/ <<e>>/*variable,readonly*/ = <<given_Char>>/*variable,readonly*/
<<val>>/*keyword*/ <<f>>/*variable,readonly*/ = <<given_Float>>/*variable,readonly*/
<<val>>/*keyword*/ <<g>>/*variable,readonly*/ = <<`* *`>>/*variable,readonly*/
<<val>>/*keyword*/ <<i>>/*variable,readonly*/ = <<X>>/*class*/.<<of>>/*method*/[<<Int>>/*class,abstract*/]
<<val>>/*keyword*/ <<x>>/*variable,readonly*/ = <<given_Xg>>/*class*/
<<val>>/*keyword*/ <<y>>/*variable,readonly*/ = <<given_Yg>>/*method*/
<<val>>/*keyword*/ <<z>>/*variable,readonly*/ = <<given_Zg_T>>/*method*/[<<String>>/*type*/]