<<package>>/*keyword*/ <<example>>/*namespace*/

<<import>>/*keyword*/ <<reflect>>/*namespace*/.<<Selectable>>/*class*/.<<reflectiveSelectable>>/*method*/

<<object>>/*keyword*/ <<StructuralTypes>>/*class*/:
  <<type>>/*keyword*/ <<User>>/*type,definition*/ = {
    <<def>>/*keyword*/ <<name>>/*method,declaration*/: <<String>>/*type*/
    <<def>>/*keyword*/ <<age>>/*method,declaration*/: <<Int>>/*class,abstract*/
  }

  <<val>>/*keyword*/ <<user>>/*variable,definition,readonly*/ = <<null>>/*keyword*/.<<asInstanceOf>>/*method*/[<<User>>/*type*/]
  <<user>>/*variable,readonly*/.<<name>>/*method*/
  <<user>>/*variable,readonly*/.<<age>>/*method*/

  <<val>>/*keyword*/ <<V>>/*variable,definition,readonly*/: <<Object>>/*class*/ {
    <<def>>/*keyword*/ <<scalameta>>/*method,declaration*/: <<String>>/*type*/
  } = <<new>>/*keyword*/:
    <<def>>/*keyword*/ <<scalameta>>/*method,definition*/ = <<"4.0">>/*string*/
  <<V>>/*variable,readonly*/.<<scalameta>>/*method*/
<<end>>/*keyword*/ <<StructuralTypes>>/*class,definition*/