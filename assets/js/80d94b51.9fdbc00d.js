"use strict";(self.webpackChunk=self.webpackChunk||[]).push([["7539"],{70710:function(e,t,s){s.r(t),s.d(t,{assets:function(){return o},contentTitle:function(){return r},default:function(){return d},frontMatter:function(){return a},metadata:function(){return i},toc:function(){return h}});var i=s(16280),n=s(85893),l=s(50065);let a={authors:"tgodzik",title:"Metals v0.7.2 - Thorium"},r=void 0,o={authorsImageUrls:[void 0]},h=[{value:"TL;DR",id:"tldr",level:2},{value:"Fix completions in case of inline comments",id:"fix-completions-in-case-of-inline-comments",level:2},{value:"Add support for non-directory sources",id:"add-support-for-non-directory-sources",level:2},{value:"Automatically add package definition to new source files",id:"automatically-add-package-definition-to-new-source-files",level:2},{value:"Automatically insert pipes in multiline string",id:"automatically-insert-pipes-in-multiline-string",level:2},{value:"Add support for Scala 2.12.9",id:"add-support-for-scala-2129",level:2},{value:"Miscellaneous fixes",id:"miscellaneous-fixes",level:2},{value:"Contributors",id:"contributors",level:2},{value:"Merged PRs",id:"merged-prs",level:2},{value:"v0.7.2 (2019-08-29)",id:"v072-2019-08-29",level:2}];function c(e){let t={a:"a",code:"code",h2:"h2",img:"img",li:"li",p:"p",pre:"pre",strong:"strong",ul:"ul",...(0,l.a)(),...e.components};return(0,n.jsxs)(n.Fragment,{children:[(0,n.jsx)(t.p,{children:'We are excited to announce the release of Metals v0.7.2 - codename "Thorium" \uD83C\uDF89\nThe release includes mostly bug fixes with some smaller new features. We are\nskipping over 0.7.1 due to a critical bug we discovered'}),"\n",(0,n.jsxs)(t.p,{children:["Metals is a language server for Scala that works with VS Code, Atom, Vim,\nSublime Text and Emacs. Metals is developed at the\n",(0,n.jsx)(t.a,{href:"https://scala.epfl.ch/",children:"Scala Center"})," and ",(0,n.jsx)(t.a,{href:"https://virtuslab.com",children:"VirtusLab"}),"\nalong with contributors from the community."]}),"\n",(0,n.jsxs)(t.p,{children:["In this release we merged 39 PRs and closed 11 issues, full details:\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/milestone/14?closed=1",children:"https://github.com/scalameta/metals/milestone/14?closed=1"})]}),"\n",(0,n.jsx)(t.h2,{id:"tldr",children:"TL;DR"}),"\n",(0,n.jsxs)(t.p,{children:["Check out the website and give Metals a try: ",(0,n.jsx)(t.a,{href:"https://scalameta.org/metals/",children:"https://scalameta.org/metals/"})]}),"\n",(0,n.jsxs)(t.ul,{children:["\n",(0,n.jsx)(t.li,{children:"support for Scala 2.12.9"}),"\n",(0,n.jsx)(t.li,{children:"fix completions in case of inline comments"}),"\n",(0,n.jsx)(t.li,{children:"add support for non-directory sources"}),"\n",(0,n.jsx)(t.li,{children:"automatically add package definition to new source files"}),"\n",(0,n.jsx)(t.li,{children:"automatically insert pipes in multiline string"}),"\n",(0,n.jsx)(t.li,{children:"a lot of miscellaneous fixes"}),"\n"]}),"\n",(0,n.jsx)(t.h2,{id:"fix-completions-in-case-of-inline-comments",children:"Fix completions in case of inline comments"}),"\n",(0,n.jsx)(t.p,{children:"Previously, completions after a comment and newline would yield no results:"}),"\n",(0,n.jsx)(t.pre,{children:(0,n.jsx)(t.code,{className:"language-scala",children:"val List(1,2,3)\n  .map(num => num + 1) // comment\n  @@\n"})}),"\n",(0,n.jsx)(t.p,{children:"This was fixed by the Metals team in the presentation compiler itself for 2.12.9\nand 2.13.1, however we also added a workaround for all supported older Scala\nversions."}),"\n",(0,n.jsx)(t.h2,{id:"add-support-for-non-directory-sources",children:"Add support for non-directory sources"}),"\n",(0,n.jsxs)(t.p,{children:["In case that your build tool supports having single files as sources, we now\noffer full support for them. That corresponds to the most recent BSP\nspecification\n",(0,n.jsx)(t.a,{href:"https://github.com/scalacenter/bsp/blob/master/docs/bsp.md#build-target-sources-request",children:"request"})]}),"\n",(0,n.jsx)(t.h2,{id:"automatically-add-package-definition-to-new-source-files",children:"Automatically add package definition to new source files"}),"\n",(0,n.jsx)(t.p,{children:"For every new Scala file we automatically add package definition based on the\nrelative path between the new source file and source root. This is done using\napplyWorkspaceEdit method, which causes the change to be done in editor and this\ncan easily be rewerted like any other change you would type."}),"\n",(0,n.jsx)(t.p,{children:(0,n.jsx)(t.img,{src:"https://i.imgur.com/6V9gHnM.gif",alt:"add-package"})}),"\n",(0,n.jsxs)(t.p,{children:["We also added support for package objects. Whenever you create ",(0,n.jsx)(t.code,{children:"package.scala"}),"\nfile, we will automatically create the barebone definition of the package\nobject."]}),"\n",(0,n.jsx)(t.p,{children:(0,n.jsx)(t.img,{src:"https://i.imgur.com/CfF0cdE.gif",alt:"package-object"})}),"\n",(0,n.jsx)(t.p,{children:"There is still some work to be done about moving files between packages, which\nwill be addressed in the next release."}),"\n",(0,n.jsx)(t.p,{children:"Big thanks to the first time contributor from VirtusLab Tomasz Dudzik for\nimplementing most of the functionality! \uD83C\uDF89"}),"\n",(0,n.jsx)(t.h2,{id:"automatically-insert-pipes-in-multiline-string",children:"Automatically insert pipes in multiline string"}),"\n",(0,n.jsxs)(t.p,{children:["We finally managed to properly support adding ",(0,n.jsx)(t.code,{children:"|"})," in multiline strings using the\n",(0,n.jsx)(t.code,{children:"onTypeFormatting"})," method. Previous implementation was very naive and often\nadded pipes in wrong places."]}),"\n",(0,n.jsx)(t.p,{children:(0,n.jsx)(t.img,{src:"https://i.imgur.com/iXGYOf0.gif",alt:"pipes"})}),"\n",(0,n.jsxs)(t.p,{children:["To enable the functionality you need to enable ",(0,n.jsx)(t.code,{children:"onTypeFormatting"})," inside your\neditor if it's not enabled by default."]}),"\n",(0,n.jsxs)(t.p,{children:["In Visual Studio Code this needs to be done in settings by checking\n",(0,n.jsx)(t.code,{children:"Editor: Format On Type"}),":"]}),"\n",(0,n.jsx)(t.p,{children:(0,n.jsx)(t.img,{src:"https://i.imgur.com/4eVvSP5.gif",alt:"on-type"})}),"\n",(0,n.jsx)(t.p,{children:"You will also need the newest version of the Visual Studio Code plugin."}),"\n",(0,n.jsx)(t.p,{children:"There is still some possible improvements in case of copy pasting entire text\nfragments, but that needs some more work."}),"\n",(0,n.jsx)(t.p,{children:"Big thanks to another of our first time contributors from VirtusLab Karolina\nBogacka! Great work! \uD83C\uDF89"}),"\n",(0,n.jsx)(t.h2,{id:"add-support-for-scala-2129",children:"Add support for Scala 2.12.9"}),"\n",(0,n.jsx)(t.p,{children:"Metals now supports Scala 2.12.9! No new Scala version has been deprecated in\nthis release, since it is possible that Scala 2.12.10 version with some\nimportant fixes will be released soon."}),"\n",(0,n.jsxs)(t.p,{children:["Big thanks to ",(0,n.jsx)(t.a,{href:"https://github.com/gabro",children:"@gabro"})," for again leading the effort!\nThis took considerably more time and effort than expected."]}),"\n",(0,n.jsx)(t.h2,{id:"miscellaneous-fixes",children:"Miscellaneous fixes"}),"\n",(0,n.jsxs)(t.ul,{children:["\n",(0,n.jsx)(t.li,{children:"erroneous warning for Scala 2.11 is no longer displayed"}),"\n",(0,n.jsx)(t.li,{children:"don't infer build target for non-readonly files"}),"\n",(0,n.jsx)(t.li,{children:"fix several small issues in the tree view protocol spec"}),"\n",(0,n.jsx)(t.li,{children:"set h2.bindAddress to avoid exposing open ports (thanks @blast-hardcheese!)"}),"\n",(0,n.jsx)(t.li,{children:"resolve symlinks that are used within workspaces"}),"\n",(0,n.jsx)(t.li,{children:"fix issues when import was requested multiple times without any change"}),"\n",(0,n.jsx)(t.li,{children:"detect openjdk as a valid jdk home"}),"\n",(0,n.jsx)(t.li,{children:"warn when java sources were not found"}),"\n",(0,n.jsx)(t.li,{children:"string interpolation completions now work properly in multiline string"}),"\n",(0,n.jsx)(t.li,{children:"add ReloadDoctor to the list of all commands (thanks @kurnevsky!)"}),"\n",(0,n.jsxs)(t.li,{children:["add correct mill version in the predef script when running ",(0,n.jsx)(t.code,{children:"bloopInstall"})]}),"\n",(0,n.jsx)(t.li,{children:"SemanticDB plugin issues are no longer reported in java only projects"}),"\n",(0,n.jsx)(t.li,{children:"check if SeamnticDB really exists and only report issue when it doesn't"}),"\n",(0,n.jsx)(t.li,{children:"update default scalafmt to 2.0.1"}),"\n",(0,n.jsx)(t.li,{children:"use recommended version for build tools in UI message"}),"\n",(0,n.jsx)(t.li,{children:"fix file leak when importing large Gradle workspaces"}),"\n"]}),"\n",(0,n.jsx)(t.h2,{id:"contributors",children:"Contributors"}),"\n",(0,n.jsx)(t.p,{children:"Big thanks to everybody who contributed to this release, a lot more people\njoined this time to make the release better!"}),"\n",(0,n.jsx)(t.pre,{children:(0,n.jsx)(t.code,{children:"$ git shortlog -sn --no-merges v0.7.0..v0.7.2\nGabriele Petronella\nTomasz Godzik\n\xd3lafur P\xe1ll Geirsson\nEvgeny Kurnevsky\nMarek \u017Barnowski\nLucas Satabin\nAyoub Benali\nChris Birchall\nCompro Prasad\nDevon Stewart\nJes\xfas Mart\xednez\nKarolina Bogacka\nTomasz Dudzik\nChris Kipp\nRikito Taniguchi\n"})}),"\n",(0,n.jsx)(t.h2,{id:"merged-prs",children:"Merged PRs"}),"\n",(0,n.jsxs)(t.h2,{id:"v072-2019-08-29",children:[(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/tree/v0.7.2",children:"v0.7.2"})," (2019-08-29)"]}),"\n",(0,n.jsx)(t.p,{children:(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/compare/v0.7.0...v0.7.2",children:"Full Changelog"})}),"\n",(0,n.jsx)(t.p,{children:(0,n.jsx)(t.strong,{children:"Merged pull requests:"})}),"\n",(0,n.jsxs)(t.ul,{children:["\n",(0,n.jsxs)(t.li,{children:["Fix not closing streams when using Files.list\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/892",children:"#892"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Avoid file leaks when digesting Gradle builds\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/889",children:"#889"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Fix broken tests ",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/888",children:"#888"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Bump Metals Scala version to 2.12.9\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/887",children:"#887"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Add check mark for formatting under vim\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/885",children:"#885"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/satabin",children:"satabin"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Recommend a recent version of the build tool when incompatible or unknown\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/881",children:"#881"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/gabro",children:"gabro"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Use BuildInfo.scalaCompilerVersion over Properties.versionNumberString\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/876",children:"#876"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Remove use of early intializers\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/877",children:"#877"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/gabro",children:"gabro"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Catch ShutdownReq exception from stopping a presentation compiler.\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/873",children:"#873"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Transpose features/editors table\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/875",children:"#875"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/gabro",children:"gabro"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Make presentation compiler work on 2.12.9\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/872",children:"#872"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/gabro",children:"gabro"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Scala 2.12.9 support ",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/867",children:"#867"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/gabro",children:"gabro"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Automatically add package objects when creating package.scala file\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/866",children:"#866"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Automatically add package declaration when creating a file\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/862",children:"#862"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tdudzik",children:"tdudzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Fix TreeViewSlowSuite tests\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/864",children:"#864"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/gabro",children:"gabro"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update scalafmt to 2.0.1 ",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/863",children:"#863"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tanishiking",children:"tanishiking"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Add support for non-directory sources\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/857",children:"#857"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Report an issue only if SemanticDB file doesn't really exist.\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/853",children:"#853"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Do not report issues in Java only projects\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/850",children:"#850"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Emacs supports client commands now\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/848",children:"#848"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/kurnevsky",children:"kurnevsky"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Fix issue if exact query is longer than the class name\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/843",children:"#843"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Fix issue with wrong mill version in predef script and update millw scripts\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/842",children:"#842"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Fix Mercury blog post date by adding an unlisted redirect\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/836",children:"#836"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/gabro",children:"gabro"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update sublime doc regarding autoimport\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/839",children:"#839"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/ayoub-benali",children:"ayoub-benali"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Improve handling of the java sources\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/825",children:"#825"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/marek1840",children:"marek1840"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Add exact libraries that are expected and fix the test\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/837",children:"#837"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Add ReloadDoctor to the list of all commands.\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/830",children:"#830"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/kurnevsky",children:"kurnevsky"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Install use-package if not already installed\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/828",children:"#828"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/Compro-Prasad",children:"Compro-Prasad"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Fix multiline edits ",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/826",children:"#826"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Timestamps should use currentTimeMillis\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/822",children:"#822"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["resolve symlinks from client requests\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/824",children:"#824"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/marek1840",children:"marek1840"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Setting h2.bindAddress to avoid exposing open ports\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/821",children:"#821"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/blast-hardcheese",children:"blast-hardcheese"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Use Scalafmt 2.0.0 when setting the version in .scalafmt.conf\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/806",children:"#806"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/gabro",children:"gabro"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Don't infer build target for non-readonly files.\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/810",children:"#810"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Fix versions in docs - wrong number of @ was used\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/820",children:"#820"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update the list of supported Scala binary versions\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/818",children:"#818"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/cb372",children:"cb372"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Bump mill version ",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/817",children:"#817"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/satabin",children:"satabin"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Send treeViewDidChange only when the client supports it\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/816",children:"#816"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/kurnevsky",children:"kurnevsky"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Fix issue with missing completions after comment and newline.\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/814",children:"#814"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Fix several issues in the tree view protocol spec\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/811",children:"#811"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Make test-release.sh script check that artifacts have synced to Maven\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/812",children:"#812"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["nvim version and updated info about required java versions\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/807",children:"#807"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Lost a word in the last PR and adding it now\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/805",children:"#805"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Use sonatype",":public"," in our own meta build\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/803",children:"#803"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/gabro",children:"gabro"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Fix an issue with wrong deprecation warning for 2.11 and update snapshot\nversion ",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/804",children:"#804"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Fix version typo ",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/802",children:"#802"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/JesusMtnez",children:"JesusMtnez"}),")"]}),"\n"]})]})}function d(e={}){let{wrapper:t}={...(0,l.a)(),...e.components};return t?(0,n.jsx)(t,{...e,children:(0,n.jsx)(c,{...e})}):c(e)}},50065:function(e,t,s){s.d(t,{Z:function(){return r},a:function(){return a}});var i=s(67294);let n={},l=i.createContext(n);function a(e){let t=i.useContext(l);return i.useMemo(function(){return"function"==typeof e?e(t):{...t,...e}},[t,e])}function r(e){let t;return t=e.disableParentContext?"function"==typeof e.components?e.components(n):e.components||n:a(e.components),i.createElement(l.Provider,{value:t},e.children)}},16280:function(e){e.exports=JSON.parse('{"permalink":"/metals/blog/2019/09/02/thorium","source":"@site/blog/2019-09-02-thorium.md","title":"Metals v0.7.2 - Thorium","description":"We are excited to announce the release of Metals v0.7.2 - codename \\"Thorium\\" \uD83C\uDF89","date":"2019-09-02T00:00:00.000Z","tags":[],"readingTime":6.14,"hasTruncateMarker":false,"authors":[{"name":"Tomasz Godzik","url":"https://twitter.com/TomekGodzik","imageURL":"https://github.com/tgodzik.png","key":"tgodzik","page":null}],"frontMatter":{"authors":"tgodzik","title":"Metals v0.7.2 - Thorium"},"unlisted":false,"prevItem":{"title":"Metals v0.7.5 - Thorium","permalink":"/metals/blog/2019/09/12/thorium"},"nextItem":{"title":"Metals v0.7.0 - Thorium","permalink":"/metals/blog/2019/06/28/thorium"}}')}}]);