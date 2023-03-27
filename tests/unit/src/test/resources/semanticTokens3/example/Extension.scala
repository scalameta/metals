<<package>>/*keyword*/ <<example>>/*namespace*/

<<extension>>/*keyword*/ (<<i>>/*parameter,readonly*/: <<Int>>/*class,abstract*/) <<def>>/*keyword*/ <<asString>>/*method*/: <<String>>/*type*/ = <<i>>/*parameter,readonly*/.<<toString>>/*method*/

<<extension>>/*keyword*/ (<<s>>/*parameter,readonly*/: <<String>>/*type*/)
  <<def>>/*keyword*/ <<asInt>>/*method*/: <<Int>>/*class,abstract*/ = <<s>>/*parameter,readonly*/.<<toInt>>/*method*/
  <<def>>/*keyword*/ <<double>>/*method*/: <<String>>/*type*/ = <<s>>/*parameter,readonly*/ <<*>>/*method*/ <<2>>/*number*/

<<trait>>/*keyword*/ <<AbstractExtension>>/*interface,abstract*/:
  <<extension>>/*keyword*/ (<<d>>/*parameter,readonly*/: <<Double>>/*class,abstract*/)
    <<def>>/*keyword*/ <<abc>>/*method*/: <<String>>/*type*/