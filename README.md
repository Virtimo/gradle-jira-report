# Jira Report Plugin

Create template powered reports (e.g. Changelogs) from Jira issues.

Also find the plugin at https://plugins.gradle.org/plugin/de.virtimo.jira-report

Template Engine https://freemarker.apache.org/

## Usage

Add this plugin to your project and use the task type de.virtimo.task.JiraReport

```gradle
plugins {
  id "de.virtimo.jira-report" version "1.0.0"
}

tasks.register("changelog",de.virtimo.task.JiraChangelog){
            serverUrl = "https://virtimo.atlassian.net"
            jiraProject = "FOO"
            fields = "*all"
            templateParams = [foo: "bar"]
            append = false
            jql = "fixVersion = \"1.0.0\" ORDER BY priority DESC, key ASC"
            destination = file("docs/changelog.adoc")
            templateFile = file("docs/changelog-template.ftl")
}
```

Add a Freemarker Template (https://freemarker.apache.org/) to docs/changelog-template.ftl.
The fetched issues list is available as "issues" in the template. If a templateParams map is passed, it is accessible via "params".

Example for an AsciiDoc formatted changelog
```
= Changelog

== ${params.foo}

=== Breaking Changes

<#list issues?filter(i -> i.fields.labels?seq_contains("BreakingChange")) as issue>
* [[${issue.key}]]${issue.key} - ${issue.fields.summary}
</#list>

=== Issues

<#list issues) as issue>
* [[${issue.key}]]${issue.key} - ${issue.fields.summary}
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