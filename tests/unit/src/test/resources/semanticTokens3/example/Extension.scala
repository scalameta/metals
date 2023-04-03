<<package>>/*keyword*/ <<example>>/*namespace*/

<<extension>>/*keyword*/ (<<i>>/*parameter,declaration,readonly*/: <<Int>>/*class,abstract*/) <<def>>/*keyword*/ <<asString>>/*method,definition*/: <<String>>/*type*/ = <<i>>/*parameter,readonly*/.<<toString>>/*method*/

<<extension>>/*keyword*/ (<<s>>/*parameter,declaration,readonly*/: <<String>>/*type*/)
  <<def>>/*keyword*/ <<asInt>>/*method,definition*/: <<Int>>/*class,abstract*/ = <<s>>/*parameter,readonly*/.<<toInt>>/*method*/
  <<def>>/*keyword*/ <<double>>/*method,definition*/: <<String>>/*type*/ = <<s>>/*parameter,readonly*/ <<*>>/*method*/ <<2>>/*number*/

<<trait>>/*keyword*/ <<AbstractExtension>>/*interface,abstract*/:
  <<extension>>/*keyword*/ (<<d>>/*parameter,declaration,readonly*/: <<Double>>/*class,abstract*/)
    <<def>>/*keyword*/ <<abc>>/*method,declaration*/: <<String>>/*type*/