<<package>>/*keyword*/ <<example>>/*namespace*/

<<object>>/*keyword*/ <<StructuralTypes>>/*class*/ {
  <<type>>/*keyword*/ <<User>>/*type,definition*/ = {
    <<def>>/*keyword*/ <<name>>/*method,declaration,abstract*/: <<String>>/*type*/
    <<def>>/*keyword*/ <<age>>/*method,declaration,abstract*/: <<Int>>/*class,abstract*/
  }

  <<val>>/*keyword*/ <<user>>/*variable,definition,readonly*/ = <<null>>/*keyword*/.<<asInstanceOf>>/*method*/[<<User>>/*type*/]
  <<user>>/*variable,readonly*/.<<name>>/*method,abstract*/
  <<user>>/*variable,readonly*/.<<age>>/*method,abstract*/

  <<val>>/*keyword*/ <<V>>/*variable,definition,readonly*/: <<Object>>/*class*/ {
    <<def>>/*keyword*/ <<scalameta>>/*method,declaration,abstract*/: <<String>>/*type*/
  } = <<new>>/*keyword*/ {
    <<def>>/*keyword*/ <<scalameta>>/*method,definition*/ = <<"4.0">>/*string*/
  }
  <<V>>/*variable,readonly*/.<<scalameta>>/*method,abstract*/
}