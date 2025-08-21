package de.virtimo.task

import freemarker.core.Environment
import freemarker.template.Configuration
import freemarker.template.Template
import freemarker.template.TemplateException
import freemarker.template.TemplateExceptionHandler
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.InvalidUserCodeException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
/**
 * @author Andre Schlegel-Tylla
 */
abstract class JiraReport extends DefaultTask{

    @Input
    abstract Property<String> getServerUrl()

    @Input
    abstract Property<String> getJiraProject()

    @Input
    @org.gradle.api.tasks.Optional
    abstract Property<String> getJql()

    @Input
    @org.gradle.api.tasks.Optional
    abstract Property<String> getUsername()

    @Input
    @org.gradle.api.tasks.Optional
    abstract Property<String> getPassword()

    @Input
    @org.gradle.api.tasks.Optional
    abstract Property<String> getFields()

    @Input
    @org.gradle.api.tasks.Optional
    abstract Property<Boolean> getAppend()

    @InputFile
    abstract RegularFileProperty getTemplateFile()

    @Input
    @org.gradle.api.tasks.Optional
    abstract MapProperty<String,Object> getTemplateParams()

    @OutputFile
    @org.gradle.api.tasks.Optional
    abstract RegularFileProperty getDestination()

    @TaskAction
    def createReport() {
        def file = getDestination().get().asFile
        file.parentFile.mkdirs()

        def jiraFields = fields.getOrElse("summary,issuetype,fixVersions,labels,components")
        project.logger.info("fields: ${jiraFields}")

        def jiraQuery = "project=${jiraProject.get()}"
        if (jql.getOrNull() != null) {
            jiraQuery = "${jiraQuery} AND ${jql.get()}"
        }
        project.logger.info("jiraQuery: ${jiraQuery}")

        String jiraUser = (project.properties['username'] as String) ?: username.getOrNull()
        if (jiraUser == null || jiraUser.trim().isEmpty()) {
            throw new InvalidUserCodeException("Missing username") as Throwable
        }

        String jiraPassword = (project.properties['password'] as String) ?: password.getOrNull()
        if (jiraPassword == null || jiraPassword.trim().isEmpty()) {
            throw new InvalidUserCodeException("Missing password") as Throwable
        }

        def normalizedBase = normalizeBaseUrl((project.properties['server'] as String) ?: serverUrl.get())
        project.logger.info("serverUrl (normalized): ${normalizedBase}")

        HttpClient client = HttpClient.newBuilder().build()
        def authToken = Base64.getEncoder().encodeToString("${jiraUser}:${jiraPassword}".getBytes(StandardCharsets.UTF_8))

        int startAt = 0
        Map data
        List issues = []
        def slurper = new JsonSlurper()
        do {
            project.logger.lifecycle("startAt ${startAt}")

            Map<String, Object> queryMap = [
                fields    : jiraFields,
                maxResults: 1000,
                startAt   : startAt,
                jql       : jiraQuery
            ] as Map<String, Object>
            String params = queryMap.collect { k, v ->
                "${URLEncoder.encode(k.toString(), 'UTF-8')}=${URLEncoder.encode(v.toString(), 'UTF-8')}"
            }.join('&')

            URI uri = URI.create("${normalizedBase}/rest/api/2/search?${params}")

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .GET()
                    .header("Authorization", "Basic ${authToken}")
                    .header("Accept", "application/json")
                    .build()

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() >= 400) {
                throw new InvalidUserCodeException("Jira-Request fehlgeschlagen: HTTP ${response.statusCode()} - ${response.body()}") as Throwable
            }

            Map resObj = slurper.parseText(response.body()) as Map
            data = resObj
            List pageIssues = (resObj.issues instanceof List) ? (List) resObj.issues : []
            issues.addAll(pageIssues)

            int pageStart = asIntSafe(resObj.startAt)
            int pageMax   = asIntSafe(resObj.maxResults)
            int total     = asIntSafe(resObj.total)

            project.logger.lifecycle("startAt=${pageStart} - total=${total} - max=${pageMax}")
            startAt = pageStart + pageMax

            if (total == 0) {
                break
            }
        } while (startAt < asIntSafe(data.total))

        data.issues = issues

        if (templateParams.present) {
            data.params = templateParams.get()
        }

        if (data instanceof Map) {
            data.each { key, value ->
                project.logger.debug("Found: ${key}")
            }
        }

        project.logger.lifecycle("Issue count: ${issues.size}")

        Configuration cfg = new Configuration(Configuration.VERSION_2_3_29)
        cfg.setDirectoryForTemplateLoading(project.projectDir)
        cfg.setDefaultEncoding("UTF-8")
        cfg.setTemplateExceptionHandler(
                new TemplateExceptionHandler() {
                    void handleTemplateException(TemplateException te, Environment env, Writer out)
                            throws TemplateException {
                        if (!env.isInAttemptBlock()) {
                            PrintWriter pw = (out instanceof PrintWriter) ? (PrintWriter) out : new PrintWriter(out)
                            pw.print("FreeMarker template error\n")
                            te.printStackTrace(pw, false, true, false)
                            pw.flush()
                        }
                    }
                }
        )
        cfg.setLogTemplateExceptions(false)
        cfg.setWrapUncheckedExceptions(true)
        cfg.setFallbackOnNullLoopVariable(false)

        Template temp = new Template("template", new FileReader(templateFile.get().asFile), cfg)
        temp.process(data, new FileWriter(file, append.getOrElse(false)))

        project.logger.lifecycle("Created file ${file}")
    }

    private static String normalizeBaseUrl(String raw) {
        String s = (raw ?: "").trim()
        if (s.equalsIgnoreCase("null") || s.isEmpty()) {
            throw new InvalidUserCodeException("Missing serverUrl") as Throwable
        }
        if (!s.contains("://")) {
            s = "https://${s}"
        }
        // Abschließende Slashes entfernen
        return s.replaceAll(/\/+$/, '')
    }

    private static int asIntSafe(Object v) {
        if (v instanceof Number) return ((Number) v).intValue()
        if (v == null) return 0
        def s = v.toString().trim()
        if (s.isEmpty()) return 0
        try {
            return Integer.parseInt(s)
        } catch (Exception ignore) {
            return 0
        }
    }
}
