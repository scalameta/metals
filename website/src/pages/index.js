import React from "react";
import Link from "@docusaurus/Link";
import useDocusaurusContext from "@docusaurus/useDocusaurusContext";
import Layout from "@theme/Layout";

const Supported = (props) => {
  const companies = [
    {
      name: "Scala Center",
      image: "img/scala-center-swirl.png",
      link: "https://scala.epfl.ch",
    },
    {
      name: "VirtusLab",
      image: "img/VirtusLab_logo_narrow.png",
      link: "https://virtuslab.com",
    },
  ];
  return (
    <div className="text--center ">
      <div className="padding--md">
        <h2 className="hero__subtitle padding-vert--md">Supported by: </h2>

        {companies.map((company) => (
          <a href={company.link}>
            <img src={company.image} title={company.name}></img>
          </a>
        ))}
      </div>
    </div>
  );
};

const Features = (props) => {
  const features = [
    {
      title: "Simple installation",
      content: "Open a directory, import your build and start coding.",
      image: "https://github.com/scalameta/gh-pages-images/blob/master/metals/index/L5CurFG.png?raw=true",
      imageAlign: "left",
    },
    {
      title: "Accurate diagnostics",
      content:
        "Compile on file save and see errors from the build tool directly inside the editor. No more switching focus to the console.",
      image: "https://github.com/scalameta/gh-pages-images/blob/master/metals/index/JYLQGrc.gif?raw=true",
      imageAlign: "right",
    },
    {
      title: "Rich build tool support",
      content:
        `The build tools sbt, Gradle, Maven and Mill are supported thanks to Bloop` +
        `Hot incremental compilation in the Bloop build server ensures compile errors appear as quickly as possible.`,
      image:
        "https://user-images.githubusercontent.com/1408093/68486864-dd9f2b00-01f6-11ea-9291-d3a7ce6ef225.png",
      imageAlign: "left",
    },
    {
      title: "Goto definition",
      content:
        "Jump to symbol definitions in your project sources and Scala/Java library dependencies.",
      image: "https://github.com/scalameta/gh-pages-images/blob/master/metals/index/bCIhFof.gif?raw=true",
      imageAlign: "right",
    },
    {
      title: "Completions",
      content:
        "Explore new library APIs, implement interfaces, generate exhaustive matches and more.",
      image:
        "https://user-images.githubusercontent.com/1408093/56036958-725bac00-5d2e-11e9-9cf7-46249125494a.gif",
      imageAlign: "left",
    },
    {
      title: "Hover (aka. type at point)",
      content: "See the expression type and symbol signature under the cursor.",
      image: "https://github.com/scalameta/gh-pages-images/blob/master/metals/index/2MfQvsM.gif?raw=true",
      imageAlign: "right",
    },
    {
      title: "Signature help (aka. parameter hints)",
      content:
        "View a method signature and method overloads as you fill in the arguments.",
      image: "https://github.com/scalameta/gh-pages-images/blob/master/metals/index/DAWIrHu.gif?raw=true",
      imageAlign: "left",
    },
    {
      title: "Find symbol references",
      content: "Find all usages of a symbol in the workspace.",
      image:
        "https://user-images.githubusercontent.com/1408093/51089190-75fc8880-1769-11e9-819c-95262205e95c.png",
      imageAlign: "right",
    },
    {
      title: "Fuzzy symbol search",
      content: "Search for symbols in the workspace or library dependencies.",
      image: "https://github.com/scalameta/gh-pages-images/blob/master/metals/index/w5yrK1w.gif?raw=true",
      imageAlign: "left",
    },
  ];
  return (
    <div>
      {features.map((feature) => (
        <div className="hero text--center" key={feature.title}>
          <div
            className={`container ${
              feature.imageAlign === "right" ? "flex-row" : "flex-row-reverse"
            }`}
          >
            <div className="padding--md">
              <h2 className="hero__subtitle">{feature.title}</h2>
              <p>{feature.content}</p>
            </div>
            <div className="padding-vert--md">
              <img src={feature.image} />
            </div>
          </div>
        </div>
      ))}
    </div>
  );
};

const Index = (props) => {
  const { siteConfig } = useDocusaurusContext();
  return (
    <Layout title={siteConfig.title} description={siteConfig.tagline}>
      <div className="hero text--center">
        <div className="container ">
          <div className="padding-vert--md">
            <h1 className="hero__title">{siteConfig.title}</h1>
            <p className="hero__subtitle">{siteConfig.tagline}</p>
          </div>
          <div>
            <Link
              to="/metals/docs"
              className="button button--lg button--outline button--primary margin--sm"
            >
              Get started
            </Link>
            <Features />
            <Supported />
          </div>
        </div>
      </div>
    </Layout>
  );
};

export default Index;
