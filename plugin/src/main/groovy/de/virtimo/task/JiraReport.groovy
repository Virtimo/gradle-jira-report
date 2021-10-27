package de.virtimo.task

import freemarker.core.Environment
import freemarker.template.Configuration
import freemarker.template.Template
import freemarker.template.TemplateException
import freemarker.template.TemplateExceptionHandler
import groovyx.net.http.RESTClient
import org.gradle.api.DefaultTask
import org.gradle.api.InvalidUserCodeException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Run a JIRA report using FreeMarker template
 */
abstract class JiraReport extends DefaultTask {

    @Input
    abstract Property<String> getServerUrl()

    @Input
    abstract Property<String> getJql()

    @Input
    abstract Property<String> getUsername()

    @Input
    abstract Property<String> getPassword()

    @Input
    @Optional
    abstract Property<String> getFields()

    @Input
    @Optional
    abstract Property<Boolean> getAppend()

    @InputFile
    abstract RegularFileProperty getTemplateFile()

    @Input
    @Optional
    abstract MapProperty<String, Object> getTemplateParams()

    @OutputFile
    abstract RegularFileProperty getDestination()

    @TaskAction
    def createReport() {
        // lets prepare all params
        def file = getDestination().get().asFile
        file.parentFile.mkdirs()

        def jiraFields = fields.getOrElse("summary,issuetype,fixVersions,labels,components")

        def jiraUser = username.getOrNull()
        if (jiraUser == null) {
            throw new InvalidUserCodeException("Missing username")
        }

        def jiraPassword = password.getOrNull()
        if (jiraPassword == null) {
            throw new InvalidUserCodeException("Missing password")
        }

        def jiraREST = new RESTClient("${serverUrl.get()}/rest/api/3/search")
        jiraREST.headers = [
                'Authorization': 'Basic ' + "${jiraUser}:${jiraPassword}".bytes.encodeBase64()
        ]

        // paginate through all issues
        def responseOk = true
        def startAt = 0
        def issues = []
        def data
        do {
            def res = jiraREST.get(query: [fields: jiraFields, maxResults: 1000, startAt: startAt, jql: jql])
            data = res.getData()
            issues.addAll(data.issues)
            startAt = data.startAt + data.maxResults
        } while (responseOk && (startAt < data.total))

        Map<String, Object> templateData = [
                issues   : data.issues,
                serverUrl: serverUrl,
        ]
        if (templateParams.present) {
            templateData.putAll(templateParams.get()) // merge all parameters into data object
        }

        // Create your Configuration instance, and specify if up to what FreeMarker
        // version (here 2.3.29) do you want to apply the fixes that are not 100%
        // backward-compatible. See the Configuration JavaDoc for details.
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_29);
        cfg.setDirectoryForTemplateLoading(project.projectDir)
        cfg.setDefaultEncoding("UTF-8");

        // During web page *development* TemplateExceptionHandler.HTML_DEBUG_HANDLER is better.
        cfg.setTemplateExceptionHandler(
                new TemplateExceptionHandler() {
                    @Override
                    public void handleTemplateException(TemplateException te, Environment env, Writer out)
                            throws TemplateException {
                        if (!env.isInAttemptBlock()) {
                            PrintWriter pw = (out instanceof PrintWriter) ? (PrintWriter) out : new PrintWriter(out);
                            pw.print("FreeMarker template error\n");
                            te.printStackTrace(pw, false, true, false);
                            pw.flush();  // To commit the HTTP response
                        }
                    }
                }

        )

        cfg.setLogTemplateExceptions(false);
        // Don't log exceptions inside FreeMarker that it will thrown at you anyway
        cfg.setWrapUncheckedExceptions(true);
        // Wrap unchecked exceptions thrown during template processing into TemplateExceptions
        cfg.setFallbackOnNullLoopVariable(false);
        // Do not fall back to higher scopes when reading a null loop variable

        try (def reader = new FileReader(templateFile.get().asFile);
             def writer = new FileWriter(file, append.getOrElse(false))) {
            Template temp = new Template("template", reader, cfg)
            Environment env = temp.createProcessingEnvironment(data, writer)
            env.process()
        }
    }
}
