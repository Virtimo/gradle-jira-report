package de.virtimo.task

import freemarker.core.Environment
import freemarker.template.Configuration
import freemarker.template.Template
import freemarker.template.TemplateException
import freemarker.template.TemplateExceptionHandler
import groovyx.net.http.AuthConfig
import groovyx.net.http.EncoderRegistry
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
 * @author Andre Schlegel-Tylla
 */
abstract class JiraReport extends DefaultTask{

    @Input
    abstract Property<String> getServerUrl()

    @Input
    abstract Property<String> getJiraProject()

    @Input
    @Optional
    abstract Property<String> getJql()

    @Input
    @Optional
    abstract Property<String> getUsername()

    @Input
    @Optional
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
    abstract MapProperty<String,Object> getTemplateParams()

    @OutputFile
    @Optional
    abstract RegularFileProperty getDestination()

    @TaskAction
    def createReport() {
        // lets prepare all params
        def file = getDestination().get().asFile
        file.parentFile.mkdirs()

        def jiraFields = fields.getOrElse("summary,issuetype,fixVersions,labels,components")
        project.logger.info("fields: ${jiraFields}")

        def jiraQuery = "project=${jiraProject.get()}"
        if(jql.getOrNull() != null){
            jiraQuery = "${jiraQuery} AND ${jql.get()}"
        }
        project.logger.info("jiraQuery: ${jiraQuery}")

        def String jiraUser = project.properties.username ?: username.getOrNull()
        if(jiraUser == null){
            throw new InvalidUserCodeException("Missing username")
        }

        def String jiraPassword = project.properties.password ?: password.getOrNull()
        if(jiraPassword == null){
            throw new InvalidUserCodeException("Missing password")
        }

        def jiraREST = new RESTClient("${serverUrl.get()}/rest/api/2/")
        jiraREST.headers = [
                'Authorization'    : ('Basic ' + "${jiraUser}:${jiraPassword}".bytes.encodeBase64().toString())
        ]

        // get all issues
        def responseOk = true
        def startAt = 0
        def data
        def issues = []
        do{
            println("startAt " + startAt)
            def res = jiraREST.get(path: "search", query: [fields: jiraFields, maxResults: 1000, startAt: startAt, jql: jiraQuery]) // AND fixVersion = "BPC 3.3.4"
            data = res.getData()
            issues.addAll(data.issues)
            println("startAt=${data.startAt} - total=${data.total} - max=${data.maxResults}")
            startAt = data.startAt + data.maxResults
        } while (responseOk && (startAt < data.total))

        data.issues = issues

        if (templateParams.present) {
            data.params = templateParams.get()
        }

        if (data instanceof Map){
            data.each {
                key, value ->
                    println("Found: " + key)
            }
        }

        println("Issue count: ${issues.size}")

        // Create your Configuration instance, and specify if up to what FreeMarker
// version (here 2.3.29) do you want to apply the fixes that are not 100%
// backward-compatible. See the Configuration JavaDoc for details.
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_29);

        cfg.setDirectoryForTemplateLoading(project.projectDir)

// From here we will set the settings recommended for new projects. These
// aren't the defaults for backward compatibilty.

// Set the preferred charset template files are stored in. UTF-8 is
// a good choice in most applications:
        cfg.setDefaultEncoding("UTF-8");

// Sets how errors will appear.
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
                    }}

                    );

// Don't log exceptions inside FreeMarker that it will thrown at you anyway:
        cfg.setLogTemplateExceptions(false);

// Wrap unchecked exceptions thrown during template processing into TemplateException-s:
        cfg.setWrapUncheckedExceptions(true);

// Do not fall back to higher scopes when reading a null loop variable:
        cfg.setFallbackOnNullLoopVariable(false);

        Template temp = new Template("template", new FileReader(templateFile.get().asFile), cfg)

        // new Template(name, READER, cfg);
        temp.process(data, new FileWriter(file, append.getOrElse(false)))


        println("Created file ")
    }
}
