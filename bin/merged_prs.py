from github import Github
import subprocess 
import re

PIPE = subprocess.PIPE

gh = Github()

# Needed data
first_tag = "v0.8.1"
last_tag = "v0.8.2"

# Running
org = gh.get_organization('scalameta')
repo = org.get_repo('metals')

tag_range = "%s..%s" % (first_tag, last_tag)
command = ['git', 'log', tag_range, "--first-parent", "master", "--pretty=format:\"%s\""]
process = subprocess.Popen(command, stdout=PIPE, stderr=PIPE)
stdoutput, stderroutput = process.communicate()

all_prs = []
for line in stdoutput.split("\n"):
    pr_num = re.findall("#\d+", line)
    all_prs.append(int(pr_num[0][1:]))

for pr in all_prs:
    try:
        pull = repo.get_pull(pr)
        print ("- %s" % pull.title)
        print ("[\#%s](%s)" % (pull.number, pull.html_url))
        print ("([%s](https://github.com/%s))" % (pull.user.login, pull.user.login))
    except Exception:
        print ("Cannot read PR %s" % pr)