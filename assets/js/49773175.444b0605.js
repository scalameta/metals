"use strict";(self.webpackChunk=self.webpackChunk||[]).push([[9284],{2934:(e,n,s)=>{s.r(n),s.d(n,{assets:()=>o,contentTitle:()=>a,default:()=>h,frontMatter:()=>l,metadata:()=>r,toc:()=>c});var i=s(4848),t=s(8453);const l={id:"vim",sidebar_label:"Vim",title:"Vim"},a=void 0,r={id:"editors/vim",title:"Vim",description:"nvim-metals demo",source:"@site/target/docs/editors/vim.md",sourceDirName:"editors",slug:"/editors/vim",permalink:"/metals/docs/editors/vim",draft:!1,unlisted:!1,editUrl:"https://github.com/scalameta/metals/edit/main/docs/editors/vim.md",tags:[],version:"current",frontMatter:{id:"vim",sidebar_label:"Vim",title:"Vim"},sidebar:"docs",previous:{title:"VS Code",permalink:"/metals/docs/editors/vscode"},next:{title:"Sublime Text",permalink:"/metals/docs/editors/sublime"}},o={},c=[{value:"Requirements",id:"requirements",level:2},{value:"nvim-metals",id:"nvim-metals",level:2},{value:"Vim alternatives",id:"vim-alternatives",level:2},{value:"Using an alternative LSP Client",id:"using-an-alternative-lsp-client",level:3},{value:"Files and Directories to include in your Gitignore",id:"files-and-directories-to-include-in-your-gitignore",level:2},{value:"Running scalafix rules",id:"running-scalafix-rules",level:2},{value:"Getting help",id:"getting-help",level:2}];function d(e){const n={a:"a",code:"code",h2:"h2",h3:"h3",img:"img",li:"li",ol:"ol",p:"p",pre:"pre",strong:"strong",ul:"ul",...(0,t.R)(),...e.components};return(0,i.jsxs)(i.Fragment,{children:[(0,i.jsx)(n.p,{children:(0,i.jsx)(n.img,{src:"https://i.imgur.com/BglIFli.gif",alt:"nvim-metals demo"})}),"\n",(0,i.jsxs)(n.p,{children:["While Metals works with most LSP clients for ",(0,i.jsx)(n.a,{href:"https://www.vim.org/",children:"Vim"})," and\n",(0,i.jsx)(n.a,{href:"https://neovim.io/",children:"Neovim"}),", we recommend using the dedicated Neovim plugin to\nget the best Metals support. Metals has many specific commands and LSP\nextensions that won't be available when not using the extension."]}),"\n",(0,i.jsx)(n.h2,{id:"requirements",children:"Requirements"}),"\n",(0,i.jsxs)(n.p,{children:[(0,i.jsx)(n.strong,{children:"Java 11, 17 provided by OpenJDK or Oracle"}),". Eclipse OpenJ9 is not\nsupported, please make sure the ",(0,i.jsx)(n.code,{children:"JAVA_HOME"})," environment variable\npoints to a valid Java 11 or 17 installation."]}),"\n",(0,i.jsxs)(n.p,{children:[(0,i.jsx)(n.strong,{children:"macOS, Linux or Windows"}),". Metals is developed on many operating systems and\nevery PR is tested on Ubuntu, Windows and MacOS."]}),"\n",(0,i.jsxs)(n.p,{children:[(0,i.jsx)(n.strong,{children:"Scala 2.13, 2.12, 2.11 and Scala 3"}),". Metals supports these Scala versions:"]}),"\n",(0,i.jsxs)(n.ul,{children:["\n",(0,i.jsxs)(n.li,{children:["\n",(0,i.jsxs)(n.p,{children:[(0,i.jsx)(n.strong,{children:"Scala 2.11"}),":\n2.11.12"]}),"\n"]}),"\n",(0,i.jsxs)(n.li,{children:["\n",(0,i.jsxs)(n.p,{children:[(0,i.jsx)(n.strong,{children:"Scala 2.12"}),":\n2.12.12, 2.12.13, 2.12.14, 2.12.15, 2.12.16, 2.12.17, 2.12.18, 2.12.19"]}),"\n"]}),"\n",(0,i.jsxs)(n.li,{children:["\n",(0,i.jsxs)(n.p,{children:[(0,i.jsx)(n.strong,{children:"Scala 2.13"}),":\n2.13.7, 2.13.8, 2.13.9, 2.13.10, 2.13.11, 2.13.12, 2.13.13, 2.13.14"]}),"\n"]}),"\n",(0,i.jsxs)(n.li,{children:["\n",(0,i.jsxs)(n.p,{children:[(0,i.jsx)(n.strong,{children:"Scala 3"}),":\n3.3.1, 3.2.2, 3.3.2, 3.3.3"]}),"\n"]}),"\n"]}),"\n",(0,i.jsx)(n.p,{children:"Scala 3 versions from 3.3.4 are automatically supported by Metals."}),"\n",(0,i.jsx)(n.p,{children:"Any older Scala versions will no longer get bugfixes, but should still\nwork properly with newest Metals."}),"\n",(0,i.jsx)(n.p,{children:"Note that 2.11.x support is deprecated and it will be removed in future releases.\nIt's recommended to upgrade to Scala 2.12 or Scala 2.13"}),"\n",(0,i.jsx)(n.h2,{id:"nvim-metals",children:"nvim-metals"}),"\n",(0,i.jsxs)(n.p,{children:[(0,i.jsx)(n.a,{href:"https://github.com/scalameta/nvim-metals",children:(0,i.jsx)(n.code,{children:"nvim-metals"})})," Is the dedicated\nMetals extension for the ",(0,i.jsx)(n.a,{href:"https://neovim.io/doc/user/lsp.html",children:"built-in LSP\nsupport"})," in Neovim."]}),"\n",(0,i.jsxs)(n.p,{children:["To get started with installation please see the ",(0,i.jsx)(n.code,{children:"nvim-metals"}),"\n",(0,i.jsx)(n.a,{href:"https://github.com/scalameta/nvim-metals#prerequisites",children:"prerequisites"})," and\n",(0,i.jsx)(n.a,{href:"https://github.com/scalameta/nvim-metals#installation",children:"installation steps"}),"."]}),"\n",(0,i.jsxs)("table",{children:[(0,i.jsx)("thead",{children:(0,i.jsxs)("tr",{children:[(0,i.jsx)("th",{children:"Version"}),(0,i.jsx)("th",{children:"Published"})]})}),(0,i.jsxs)("tbody",{children:[(0,i.jsxs)("tr",{children:[(0,i.jsx)("td",{children:"1.3.4"}),(0,i.jsx)("td",{children:"24 Jul 2024 13:39"})]}),(0,i.jsxs)("tr",{children:[(0,i.jsx)("td",{children:"1.3.4+11-fc5b79b2-SNAPSHOT"}),(0,i.jsx)("td",{children:"25 Jul 2024 13:41"})]})]})]}),"\n",(0,i.jsxs)(n.p,{children:["Keep in mind that by default Neovim doesn't have default mappings for the\nfunctionality you'll want like, hovers, goto definition, method signatures, etc.\nYou can find a full example configuration of these in the ",(0,i.jsx)(n.a,{href:"https://github.com/scalameta/nvim-metals/discussions/39",children:"example\nconfiguration"}),"."]}),"\n",(0,i.jsxs)(n.p,{children:["For a guide on all the available features in ",(0,i.jsx)(n.code,{children:"nvim-metals"}),", refer to the\n",(0,i.jsx)(n.a,{href:"https://github.com/scalameta/nvim-metals/discussions/279",children:"features list"}),"."]}),"\n",(0,i.jsx)(n.h2,{id:"vim-alternatives",children:"Vim alternatives"}),"\n",(0,i.jsxs)(n.p,{children:["There are multiple Vim alternatives if you're not a Neovim user. Metals did have\na Metals-specific plugin that worked with Vim,\n",(0,i.jsx)(n.a,{href:"https://github.com/scalameta/coc-metals",children:(0,i.jsx)(n.code,{children:"coc-metals"})}),", but it doesn't work\nwith the newest versions of Metals and is currently ",(0,i.jsx)(n.a,{href:"https://github.com/scalameta/coc-metals/issues/460",children:"deprecated and\nunmaintained"}),"."]}),"\n",(0,i.jsx)(n.h3,{id:"using-an-alternative-lsp-client",children:"Using an alternative LSP Client"}),"\n",(0,i.jsx)(n.p,{children:"There are multiple other LSP clients that work with Vim. Here are a few:"}),"\n",(0,i.jsxs)(n.ul,{children:["\n",(0,i.jsxs)(n.li,{children:[(0,i.jsx)(n.a,{href:"https://github.com/natebosch/vim-lsc/",children:(0,i.jsx)(n.code,{children:"natebosch/vim-lsc"})}),": simple installation and written in Vimscript."]}),"\n",(0,i.jsxs)(n.li,{children:[(0,i.jsx)(n.a,{href:"https://github.com/prabirshrestha/vim-lsp",children:(0,i.jsx)(n.code,{children:"vim-lsp"})}),": simple installation and written in\nVimscript."]}),"\n",(0,i.jsxs)(n.li,{children:[(0,i.jsx)(n.a,{href:"https://github.com/yegappan/lsp",children:(0,i.jsx)(n.code,{children:"yegappan/lsp"})}),": Very new and targeting\nVim9."]}),"\n"]}),"\n",(0,i.jsx)(n.p,{children:"Keep in mind that they have varying levels of LSP support, don't support Metals\nspecific commands (like build import), or Metals specific LSP extensions (like\ntree view), and you need to manage the Metals installation yourself."}),"\n",(0,i.jsx)(n.p,{children:"There are two ways to install Metals yourself in order to work with an\nalternative client."}),"\n",(0,i.jsxs)(n.ol,{children:["\n",(0,i.jsxs)(n.li,{children:["Most easily is to just use ",(0,i.jsx)(n.a,{href:"https://get-coursier.io/",children:"Coursier"})," to do a ",(0,i.jsx)(n.code,{children:"cs install metals"}),"."]}),"\n",(0,i.jsx)(n.li,{children:"Generating a Metals binary yourself."}),"\n"]}),"\n",(0,i.jsx)(n.h2,{id:"files-and-directories-to-include-in-your-gitignore",children:"Files and Directories to include in your Gitignore"}),"\n",(0,i.jsxs)(n.p,{children:["The Metals server places logs and other files in the ",(0,i.jsx)(n.code,{children:".metals"})," directory. The\nBloop compile server places logs and compilation artifacts in the ",(0,i.jsx)(n.code,{children:".bloop"}),"\ndirectory. The Bloop plugin that generates Bloop configuration is added in the\n",(0,i.jsx)(n.code,{children:"metals.sbt"})," file, which is added at ",(0,i.jsx)(n.code,{children:"project/metals.sbt"})," as well as further\n",(0,i.jsx)(n.code,{children:"project"})," directories depending on how deep ",(0,i.jsx)(n.code,{children:"*.sbt"})," files need to be supported.\nTo support each ",(0,i.jsx)(n.code,{children:"*.sbt"})," file Metals needs to create an additional file at\n",(0,i.jsx)(n.code,{children:"./project/project/metals.sbt"})," relative to the sbt file.\nWorking with Ammonite scripts will place compiled scripts into the ",(0,i.jsx)(n.code,{children:".ammonite"})," directory.\nIt's recommended to exclude these directories and files\nfrom version control systems like git."]}),"\n",(0,i.jsx)(n.pre,{children:(0,i.jsx)(n.code,{className:"language-sh",children:"# ~/.gitignore\n.metals/\n.bloop/\n.ammonite/\nmetals.sbt\n"})}),"\n",(0,i.jsx)(n.pre,{children:(0,i.jsx)(n.code,{className:"language-sh",children:"coursier bootstrap org.scalameta:metals_2.13:1.3.4 -o metals -f\n"})}),"\n",(0,i.jsx)(n.p,{children:"(optional) It's recommended to enable JVM string de-duplication and provide a\ngenerous stack size and memory options."}),"\n",(0,i.jsx)(n.pre,{children:(0,i.jsx)(n.code,{className:"language-sh",children:"coursier bootstrap \\\n  --java-opt -XX:+UseG1GC \\\n  --java-opt -XX:+UseStringDeduplication  \\\n  --java-opt -Xss4m \\\n  --java-opt -Xms100m \\\n  org.scalameta:metals_2.13:1.3.4 -o metals -f\n"})}),"\n",(0,i.jsxs)(n.p,{children:["The ",(0,i.jsx)(n.code,{children:"-Dmetals.client=vim-lsc"})," flag is there just as a helper to match your\npotential client. So make sure it matches your client name. This line isn't\nmandatory though as your client should be able to fully be configured via\n",(0,i.jsx)(n.code,{children:"InitializationOptions"}),". You can read more about his\n",(0,i.jsx)(n.a,{href:"https://scalameta.org/metals/blog/2020/07/23/configuring-a-client#initializationoptions",children:"here"}),"."]}),"\n",(0,i.jsx)(n.h2,{id:"running-scalafix-rules",children:"Running scalafix rules"}),"\n",(0,i.jsxs)(n.p,{children:["Scalafix allows users to specify some refactoring and linting rules that can be applied to your\ncodebase. Please checkout the ",(0,i.jsx)(n.a,{href:"https://scalacenter.github.io/scalafix",children:"scalafix website"})," for more information."]}),"\n",(0,i.jsxs)(n.p,{children:["Since Metals v0.11.7 it's now possible to run scalafix rules using a special\ncommand ",(0,i.jsx)(n.code,{children:"metals.scalafix-run"}),".\nThis should run all the rules defined in your ",(0,i.jsx)(n.code,{children:".scalafix.conf"})," file. All built-in rules\nand the ",(0,i.jsx)(n.a,{href:"https://scalacenter.github.io/scalafix/docs/rules/community-rules.html#hygiene-rules",children:"community hygiene ones"})," can\nbe run without any additional settings. However, for all the other rules users need to\nadd an additional dependency in the ",(0,i.jsx)(n.code,{children:"metals.scalafixRulesDependencies"})," user setting.\nThose rules need to be in form of strings such as ",(0,i.jsx)(n.code,{children:"com.github.liancheng::organize-imports:0.6.0"}),", which\nfollows the same convention as ",(0,i.jsx)(n.a,{href:"https://get-coursier.io/",children:"coursier dependencies"}),"."]}),"\n",(0,i.jsx)(n.p,{children:"A sample scalafix configuration can be seen below:"}),"\n",(0,i.jsx)(n.pre,{children:(0,i.jsx)(n.code,{className:"language-hocon",children:'rules = [\n  OrganizeImports,\n  ExplicitResultTypes,\n  RemoveUnused\n]\n\nRemoveUnused.imports = false\n\nOrganizeImports.groupedImports = Explode\nOrganizeImports.expandRelative = true\nOrganizeImports.removeUnused = true\nOrganizeImports.groups = [\n  "re:javax?\\."\n  "scala."\n  "scala.meta."\n  "*"\n]\n\n'})}),"\n",(0,i.jsx)(n.h2,{id:"getting-help",children:"Getting help"}),"\n",(0,i.jsxs)(n.p,{children:["There is an active community using Vim and Metals. Apart from ",(0,i.jsx)(n.a,{href:"https://github.com/scalameta/nvim-metals/issues/new/choose",children:"creating an\nissue"})," or ",(0,i.jsx)(n.a,{href:"https://github.com/scalameta/nvim-metals/discussions",children:"starting\na discussion"})," for\n",(0,i.jsx)(n.code,{children:"nvim-metals"})," users, you can also ask questions in our ",(0,i.jsx)(n.code,{children:"#vim-users"})," ",(0,i.jsx)(n.a,{href:"https://discord.gg/FaVDrJegEh",children:"Discord\nChannel"})," or ",(0,i.jsx)(n.a,{href:"https://matrix.to/#/#scalameta:vim-users",children:"Matrix\nBridge"}),"."]})]})}function h(e={}){const{wrapper:n}={...(0,t.R)(),...e.components};return n?(0,i.jsx)(n,{...e,children:(0,i.jsx)(d,{...e})}):d(e)}},8453:(e,n,s)=>{s.d(n,{R:()=>a,x:()=>r});var i=s(6540);const t={},l=i.createContext(t);function a(e){const n=i.useContext(l);return i.useMemo((function(){return"function"==typeof e?e(n):{...n,...e}}),[n,e])}function r(e){let n;return n=e.disableParentContext?"function"==typeof e.components?e.components(t):e.components||t:a(e.components),i.createElement(l.Provider,{value:n},e.children)}}}]);