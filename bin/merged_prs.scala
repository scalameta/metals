//> using jvm 17
//> using scala 3
//> using dep org.kohsuke:github-api:1.329
//> using dep com.lihaoyi::os-lib:0.11.5
//> using options -Wunused:all -deprecation

import scala.collection.mutable.ListBuffer
import scala.collection.mutable
import scala.jdk.CollectionConverters._

import org.kohsuke.github.GitHubBuilder
import org.kohsuke.github.GHIssueState

import java.text.SimpleDateFormat
import java.util.Date

val codename = "Osmium"

@main
def main(
    firstTag: String,
    lastTag: String,
    githubToken: String,
) = {
  val author = os.proc(List("git", "config", "user.name")).call().out.trim()
  val commits = os
    .proc(List("git", "rev-list", s"${firstTag}..${lastTag}"))
    .call()
    .out
    .trim()
    .linesIterator
    .size

  val contributors = os
    .proc(
      List("git", "shortlog", "-sn", "--no-merges", s"${firstTag}..${lastTag}")
    )
    .call()
    .out
    .trim()
    .linesIterator
    .toList

  val command = List(
    "git",
    "log",
    s"$firstTag..$lastTag",
    "--format=format:%H",
    "--first-parent",
    "main",
  )

  val output = os.proc(command).call().out.trim()

  val gh = new GitHubBuilder()
    .withOAuthToken(githubToken)
    .build()

  val foundPRs = mutable.Set.empty[Int]
  val mergedPRs = ListBuffer[String]()
  for {
    // group in order to optimize API
    searchSha <-
      output.split('\n').grouped(5).map(_.mkString("SHA ", " SHA ", ""))
    allMatching =
      gh.searchIssues().q(s"repo:scalameta/metals type:pr $searchSha").list()
    pr <- allMatching.toList().asScala.sortBy(_.getClosedAt()).reverse
    prNumber = pr.getNumber()
    if !foundPRs(prNumber)
  } {
    foundPRs += prNumber
    val login = pr.getUser().getLogin()
    val formattedPR =
      s"""|- ${pr.getTitle()}
          |  [\\#${pr.getNumber()}](${pr.getHtmlUrl()})
          |  ([$login](https://github.com/$login))""".stripMargin
    mergedPRs += formattedPR
  }

  val milestone = gh
    .getRepository("scalameta/metals")
    .listMilestones(GHIssueState.OPEN)
    .asScala
    .headOption
    .map(_.getNumber())
    .getOrElse(0)

  val releaseNotes =
    template(
      author,
      firstTag,
      codename,
      lastTag,
      mergedPRs.toList,
      milestone,
      commits,
      contributors,
    )

  val pathToReleaseNotes =
    os.pwd / "website" / "blog" / s"${today}-${codename.toLowerCase()}.md"
  os.write(pathToReleaseNotes, releaseNotes)
}

def today: String = {
  val formatter = new SimpleDateFormat("yyyy-MM-dd");
  formatter.format(new Date());
}

def template(
    author: String,
    firstTag: String,
    codename: String,
    lastTag: String,
    mergedPrs: List[String],
    milestone: Int,
    commits: Int,
    contributors: List[String],
) = {
  s"""|---
      |authors: $author
      |title: Metals $lastTag - $codename
      |---
      |
      |We're happy to announce the release of Metals $lastTag, which
      |
      |<table>
      |<tbody>
      |  <tr>
      |    <td>Commits since last release</td>
      |    <td align="center">$commits</td>
      |  </tr>
      |  <tr>
      |    <td>Merged PRs</td>
      |    <td align="center">${mergedPrs.size}</td>
      |  </tr>
      |    <tr>
      |    <td>Contributors</td>
      |    <td align="center">${contributors.size}</td>
      |  </tr>
      |  <tr>
      |    <td>Closed issues</td>
      |    <td align="center"></td>
      |  </tr>
      |  <tr>
      |    <td>New features</td>
      |    <td align="center"></td>
      |  </tr>
      |</tbody>
      |</table>
      |
      |For full details: [https://github.com/scalameta/metals/milestone/$milestone?closed=1](https://github.com/scalameta/metals/milestone/$milestone?closed=1)
      |
      |Metals is a language server for Scala that works with VS Code, Vim, Emacs, Zed,
      |Helix and Sublime Text. Metals is developed at the
      |[Scala Center](https://scala.epfl.ch/) and [VirtusLab](https://virtuslab.com)
      |with the help from contributors from the community.
      |
      |## TL;DR
      |
      |Check out [https://scalameta.org/metals/](https://scalameta.org/metals/), and
      |give Metals a try!
      |
      |
      |- [First item](#first-item)
      |- [Second item](#second-item)
      |
      |## First item
      |
      |## Second item
      |
      |## Contributors
      |
      |Big thanks to everybody who contributed to this release or reported an issue!
      |
      |```
      |$$ git shortlog -sn --no-merges $firstTag..$lastTag
      |${contributors.mkString("\n")}
      |```
      |
      |## Merged PRs
      |
      |## [$lastTag](https://github.com/scalameta/metals/tree/$lastTag) (${today})
      |
      |[Full Changelog](https://github.com/scalameta/metals/compare/$firstTag...$lastTag)
      |
      |**Merged pull requests:**
      |
      |${mergedPrs.mkString("\n")}
      |""".stripMargin
}
