"use strict";(self.webpackChunk=self.webpackChunk||[]).push([[3553],{5688:(e,l,i)=>{i.r(l),i.d(l,{assets:()=>r,contentTitle:()=>s,default:()=>u,frontMatter:()=>n,metadata:()=>a,toc:()=>d});var t=i(5893),o=i(1151);const n={id:"mill",title:"Mill"},s=void 0,a={id:"build-tools/mill",title:"Mill",description:"Mill is a build tool initially developed by Li Haoyi in order to create something simpler",source:"@site/target/docs/build-tools/mill.md",sourceDirName:"build-tools",slug:"/build-tools/mill",permalink:"/metals/docs/build-tools/mill",draft:!1,unlisted:!1,editUrl:"https://github.com/scalameta/metals/edit/main/docs/build-tools/mill.md",tags:[],version:"current",frontMatter:{id:"mill",title:"Mill"},sidebar:"docs",previous:{title:"Maven",permalink:"/metals/docs/build-tools/maven"},next:{title:"sbt",permalink:"/metals/docs/build-tools/sbt"}},r={},d=[{value:"Automatic installation",id:"automatic-installation",level:2},{value:"Manual installation",id:"manual-installation",level:2},{value:"Bloop",id:"bloop",level:3},{value:"Mill BSP",id:"mill-bsp",level:3}];function c(e){const l={a:"a",code:"code",h2:"h2",h3:"h3",p:"p",...(0,o.a)(),...e.components};return(0,t.jsxs)(t.Fragment,{children:[(0,t.jsxs)(l.p,{children:["Mill is a build tool initially developed by Li Haoyi in order to create something simpler\nand more intuitive than most of the other build tools today.  There is extensive\ndocumentation on the ",(0,t.jsx)(l.a,{href:"https://com-lihaoyi.github.io/mill/",children:"Mill website"}),"."]}),"\n",(0,t.jsx)(l.h2,{id:"automatic-installation",children:"Automatic installation"}),"\n",(0,t.jsx)(l.p,{children:'The first time you open Metals in a new Mill workspace you will be\nprompted to import the build. Select "Import Build" to start the\nautomatic installation. This will create all the needed Bloop config\nfiles. You should then be able to edit and compile your code utilizing\nall of the features.'}),"\n",(0,t.jsxs)(l.p,{children:["To force a Mill version you can write it to a file named ",(0,t.jsx)(l.code,{children:".mill-version"}),"\nin the workspace directory."]}),"\n",(0,t.jsx)(l.h2,{id:"manual-installation",children:"Manual installation"}),"\n",(0,t.jsx)(l.p,{children:"Manual installation is not recommended by Metals, but it's pretty easy to do.\nYou can choose between two server implementations."}),"\n",(0,t.jsx)(l.h3,{id:"bloop",children:"Bloop"}),"\n",(0,t.jsx)(l.p,{children:"Using Mill with Bloop is the current preferred way by Metals."}),"\n",(0,t.jsx)(l.p,{children:"Metals requires the Bloop config files, which you can generate with the following command:"}),"\n",(0,t.jsx)(l.p,{children:(0,t.jsx)(l.code,{children:'mill --import "ivy:com.lihaoyi::mill-contrib-bloop::" mill.contrib.bloop.Bloop/install'})}),"\n",(0,t.jsx)(l.p,{children:"Afterwards, you can just open Metals and start working on your code."}),"\n",(0,t.jsx)(l.h3,{id:"mill-bsp",children:"Mill BSP"}),"\n",(0,t.jsx)(l.p,{children:"Mill also provides a built-in BSP server. To generate the BSP connection discovery files, run the following command:"}),"\n",(0,t.jsx)(l.p,{children:(0,t.jsx)(l.code,{children:"mill mill.bsp.BSP/install"})})]})}function u(e={}){const{wrapper:l}={...(0,o.a)(),...e.components};return l?(0,t.jsx)(l,{...e,children:(0,t.jsx)(c,{...e})}):c(e)}},1151:(e,l,i)=>{i.d(l,{Z:()=>a,a:()=>s});var t=i(7294);const o={},n=t.createContext(o);function s(e){const l=t.useContext(n);return t.useMemo((function(){return"function"==typeof e?e(l):{...l,...e}}),[l,e])}function a(e){let l;return l=e.disableParentContext?"function"==typeof e.components?e.components(o):e.components||o:s(e.components),t.createElement(n.Provider,{value:l},e.children)}}}]);