import $ivy.`org.kohsuke:github-api:1.114`

import scala.collection.JavaConverters._
import scala.collection.mutable

import org.kohsuke.github.GitHubBuilder

// Last two tags to search merged PRs between
val firstTag = "v0.9.1"
val lastTag = "v0.9.2"

val command = List(
  "git",
  "log",
  s"$firstTag..$lastTag",
  "--first-parent",
  "main",
  "--pretty=format:%H"
)

val output = os.proc(command).call().out.trim()

// Basic github token needs to be added, no rights neccessary
val gh = new GitHubBuilder()
  .withOAuthToken("")
  .build()

val foundPRs = mutable.Set.empty[Int]
for {
  sha <- output.split('\n')
  allMatching =
    gh.searchIssues().q(s"repo:scalameta/metals type:pr SHA $sha").list()
  pr <- allMatching.toList().asScala.headOption
  prNumber = pr.getNumber()
  if !foundPRs(prNumber)
} {
  foundPRs += prNumber
  val login = pr.getUser().getLogin()
  val formattedPR =
    s"""|- ${pr.getTitle()}
        |  [\\#${pr.getNumber()}](${pr.getHtmlUrl()})
        |  ([$login](https://github.com/$login))""".stripMargin
  println(formattedPR)
}
