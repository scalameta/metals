"use strict";(self.webpackChunk=self.webpackChunk||[]).push([["8887"],{17618:function(e,t,s){s.r(t),s.d(t,{assets:function(){return h},contentTitle:function(){return r},default:function(){return d},frontMatter:function(){return n},metadata:function(){return a},toc:function(){return c}});var a=s(95687),l=s(85893),i=s(50065);let n={authors:"tgodzik",title:"Metals v0.9.10 - Lithium"},r=void 0,h={authorsImageUrls:[void 0]},c=[{value:"TL;DR",id:"tldr",level:2},{value:"Contributors",id:"contributors",level:2},{value:"Merged PRs",id:"merged-prs",level:2},{value:"v0.9.9 (2021-01-19)",id:"v099-2021-01-19",level:2}];function o(e){let t={a:"a",code:"code",h2:"h2",li:"li",p:"p",pre:"pre",strong:"strong",ul:"ul",...(0,i.a)(),...e.components};return(0,l.jsxs)(l.Fragment,{children:[(0,l.jsx)(t.p,{children:"We're happy to announce the release of Metals v0.9.10, which contains a number\nof fixes and more importantly support for Scala 2.12.13. We're skipping over the\nv0.9.9 release due to a significant bug that showed up after the release."}),"\n",(0,l.jsx)("table",{children:(0,l.jsxs)("tbody",{children:[(0,l.jsxs)("tr",{children:[(0,l.jsx)("td",{children:"Commits since last release"}),(0,l.jsx)("td",{align:"center",children:"90"})]}),(0,l.jsxs)("tr",{children:[(0,l.jsx)("td",{children:"Merged PRs"}),(0,l.jsx)("td",{align:"center",children:"35"})]}),(0,l.jsxs)("tr",{children:[(0,l.jsx)("td",{children:"Contributors"}),(0,l.jsx)("td",{align:"center",children:"6"})]}),(0,l.jsxs)("tr",{children:[(0,l.jsx)("td",{children:"Closed issues"}),(0,l.jsx)("td",{align:"center",children:"13"})]}),(0,l.jsxs)("tr",{children:[(0,l.jsx)("td",{children:"New features"}),(0,l.jsx)("td",{align:"center"})]})]})}),"\n",(0,l.jsxs)(t.p,{children:["For full details: ",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/milestone/33?closed=1",children:"https://github.com/scalameta/metals/milestone/33?closed=1"})]}),"\n",(0,l.jsxs)(t.p,{children:["Metals is a language server for Scala that works with VS Code, Vim, Emacs,\nSublime Text and Eclipse. Metals is developed at the\n",(0,l.jsx)(t.a,{href:"https://scala.epfl.ch/",children:"Scala Center"})," and ",(0,l.jsx)(t.a,{href:"https://virtuslab.com",children:"VirtusLab"}),"\nwith the help from ",(0,l.jsx)(t.a,{href:"https://lunatech.com",children:"Lunatech"})," along with contributors from\nthe community."]}),"\n",(0,l.jsx)(t.h2,{id:"tldr",children:"TL;DR"}),"\n",(0,l.jsxs)(t.p,{children:["Check out ",(0,l.jsx)(t.a,{href:"https://scalameta.org/metals/",children:"https://scalameta.org/metals/"}),", and\ngive Metals a try!"]}),"\n",(0,l.jsxs)(t.ul,{children:["\n",(0,l.jsx)(t.li,{children:"Support for Scala 2.12.13."}),"\n",(0,l.jsxs)(t.li,{children:["Added named argument completion for Scala 3 (thanks\n",(0,l.jsx)(t.a,{href:"https://github.com/tanishiking",children:"tanishiking"}),"!)."]}),"\n",(0,l.jsx)(t.li,{children:"Fix worksheet decorations to show up on focus."}),"\n",(0,l.jsx)(t.li,{children:"Allow find references and rename for standalone files."}),"\n",(0,l.jsx)(t.li,{children:"Fix compatibility with sbt < 1.3.0 builds."}),"\n"]}),"\n",(0,l.jsx)(t.h2,{id:"contributors",children:"Contributors"}),"\n",(0,l.jsx)(t.p,{children:"Big thanks to everybody who contributed to this release or reported an issue!"}),"\n",(0,l.jsx)(t.pre,{children:(0,l.jsx)(t.code,{children:"$ git shortlog -sn --no-merges v0.9.8..v0.9.10\nChris Kipp\nTomasz Godzik\nDavid Strawn\nCheng Lian\nRikito Taniguchi\n"})}),"\n",(0,l.jsx)(t.h2,{id:"merged-prs",children:"Merged PRs"}),"\n",(0,l.jsxs)(t.h2,{id:"v099-2021-01-19",children:[(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/tree/v0.9.10",children:"v0.9.9"})," (2021-01-19)"]}),"\n",(0,l.jsx)(t.p,{children:(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/compare/v0.9.8...v0.9.10",children:"Full Changelog"})}),"\n",(0,l.jsx)(t.p,{children:(0,l.jsx)(t.strong,{children:"Merged pull requests:"})}),"\n",(0,l.jsxs)(t.ul,{children:["\n",(0,l.jsxs)(t.li,{children:["Bump mdoc to 2.2.15 ",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2409",children:"#2409"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Update Metals Scala 2.12 version to 2.12.13\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2404",children:"#2404"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Look for clientInfo in intializeParams instead of serverConfig.\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2402",children:"#2402"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Ensure semanticdbVersion can be overwritten.\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2401",children:"#2401"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:['Revert "Add release notes for Metals 0.9.9"\n',(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2397",children:"#2397"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Add release notes for Metals 0.9.9\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2396",children:"#2396"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Add support for Scala 2.12.13\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2383",children:"#2383"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Clean up --version output.\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2393",children:"#2393"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Avoid NPE when there is no ",(0,l.jsx)(t.code,{children:"rootUri"}),".\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2391",children:"#2391"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Bare minimum arg completion for scala3\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2369",children:"#2369"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/tanishiking",children:"tanishiking"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Update flyway-core to 7.5.0\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2389",children:"#2389"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Update scribe, scribe-slf4j to 3.1.9\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2387",children:"#2387"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Update jackson-databind to 2.12.1\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2386",children:"#2386"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Update bloop-config, bloop-launcher to 1.4.6-21-464e4ec4\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2385",children:"#2385"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Fix wrong name being displayed for a selected server\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2377",children:"#2377"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Make sure that worksheet decorations are shown again when the focused\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2372",children:"#2372"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Update Vim mappings and add reference to nvim-metals.\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2367",children:"#2367"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Add in CopyWorksheetOutput to all so it's in the docs.\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2368",children:"#2368"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Refactor doctor and make sure issues with sbt are properly reported\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2339",children:"#2339"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Remove Atom from the docs.\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2364",children:"#2364"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Update scalameta, semanticdb-scalac, ... to 4.4.4\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2361",children:"#2361"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Update munit-docs, sbt-munit to 0.7.20\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2360",children:"#2360"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Update mdoc, sbt-mdoc to 2.2.14\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2359",children:"#2359"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Update sbt, scripted-plugin to 1.4.6\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2358",children:"#2358"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Update flyway-core to 7.3.2\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2357",children:"#2357"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Update coursier to 2.0.8\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2355",children:"#2355"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Update directories to 23\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2354",children:"#2354"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Update scribe, scribe-slf4j to 3.1.8\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2353",children:"#2353"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Calculate semanticdb for standalone files\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2345",children:"#2345"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Fix incompatibility between currently used Bloop and sbt versions < 1.3.0\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2350",children:"#2350"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Bump Ammonite to the 2.13.4 published version.\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2331",children:"#2331"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Fix an OrganizeImports configuration typo\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2342",children:"#2342"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/liancheng",children:"liancheng"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Update Deprecated Usage Of ",(0,l.jsx)(t.code,{children:"setDeprecated"})," from ",(0,l.jsx)(t.code,{children:"lsp4j"}),"\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2334",children:"#2334"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/isomarcte",children:"isomarcte"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Remove ",(0,l.jsx)(t.code,{children:"-XX:+CMSClassUnloadingEnabled"})," From ",(0,l.jsx)(t.code,{children:".jvmopts"}),"\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2335",children:"#2335"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/isomarcte",children:"isomarcte"}),")"]}),"\n",(0,l.jsxs)(t.li,{children:["Add release notes for 0.9.8\n",(0,l.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2332",children:"#2332"}),"\n(",(0,l.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n"]})]})}function d(e={}){let{wrapper:t}={...(0,i.a)(),...e.components};return t?(0,l.jsx)(t,{...e,children:(0,l.jsx)(o,{...e})}):o(e)}},50065:function(e,t,s){s.d(t,{Z:function(){return r},a:function(){return n}});var a=s(67294);let l={},i=a.createContext(l);function n(e){let t=a.useContext(i);return a.useMemo(function(){return"function"==typeof e?e(t):{...t,...e}},[t,e])}function r(e){let t;return t=e.disableParentContext?"function"==typeof e.components?e.components(l):e.components||l:n(e.components),a.createElement(i.Provider,{value:t},e.children)}},95687:function(e){e.exports=JSON.parse('{"permalink":"/metals/blog/2021/01/19/lithium","source":"@site/blog/2021-01-19-lithium.md","title":"Metals v0.9.10 - Lithium","description":"We\'re happy to announce the release of Metals v0.9.10, which contains a number","date":"2021-01-19T00:00:00.000Z","tags":[],"readingTime":2.645,"hasTruncateMarker":false,"authors":[{"name":"Tomasz Godzik","url":"https://twitter.com/TomekGodzik","imageURL":"https://github.com/tgodzik.png","key":"tgodzik","page":null}],"frontMatter":{"authors":"tgodzik","title":"Metals v0.9.10 - Lithium"},"unlisted":false,"prevItem":{"title":"A Metals Retrospective (Part 1)","permalink":"/metals/blog/2021/02/02/metals-retro-part1"},"nextItem":{"title":"Metals v0.9.8 - Lithium","permalink":"/metals/blog/2020/12/19/lithium"}}')}}]);