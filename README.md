# Jira Report Plugin

Create template powered reports (e.g. Changelogs) from Jira issues.

Template Engine https://freemarker.apache.org/

## Usage

Add this plugin to your project and use the task type de.virtimo.task.JiraReport

```gradle
plugins {
  id "de.virtimo.jira-report" version "1.0.0"
}

tasks.register("changelog",de.virtimo.task.JiraReport){
            serverUrl = "https://virtimo.atlassian.net"
            username = "$username"
            password = "$password"
            fields = "*all"
            templateParams = [foo: "bar"]
            append = false
            jql = "project = FOO and statusCategory = Done ORDER BY fixVersion DESC, issueType, key"
            destination = file("CHANGELOG.md")
            templateFile = file("docs/changelog-template.ftl")
}
```

Add a Freemarker Template (https://freemarker.apache.org/) to your project (.e.g `docs/changelog-template.ftl`).
The fetched issues list is available as "issues" in the template. If a templateParams map is passed, then all properties are accessible in the template.

Example for a MarkDown formatted changelog :
```
# Changelog

<#assign lastIssuetype = ""/>
<#assign lastFixVersion = ""/>
<#list issues as issue>
<#if lastFixVersion != issue.fields.fixVersions?map(v->v.name)?min!"No Version">
<#assign lastFixVersion = issue.fields.fixVersions?map(v->v.name)?min!"No Version"/>
## Version: ${lastFixVersion}
</#if>
<#if lastIssuetype != issue.fields.issuetype.name>
<#assign lastIssuetype = issue.fields.issuetype.name/>
### ${lastIssuetype}
</#if>
* [${issue.key}](https://mycompany.atlassian.net/issue/${issue.key}) - ${issue.fields.summary}
</#list>
```


```
> gradlew changelog -Pusername=virtimo@example.com -Ppassword=helloworld
```

## Contributing

Please open an issue or create a PR.

### Tests

This plugin includes some basic tests on static data.

```
./gradlew check
```

You also can check against Jira by passing username, password and serverUrl as project params.

```
./gradlew check -Pusername=virtimo@example.com -Ppassword=helloworld -Pserver=https://virtimo.atlassian.net
```