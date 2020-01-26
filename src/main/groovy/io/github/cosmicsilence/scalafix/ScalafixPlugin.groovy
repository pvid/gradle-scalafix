package io.github.cosmicsilence.scalafix

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.scala.ScalaPlugin
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.scala.ScalaCompile

/**
 * Gradle plugin for running Scalafix.
 */
class ScalafixPlugin implements Plugin<Project> {

    private static final String EXTENSION = "scalafix"
    private static final String CUSTOM_RULES_CONFIGURATION = "scalafix"
    private static final String TASK_GROUP = "scalafix"
    private static final String FIX_TASK = "scalafix"
    private static final String CHECK_TASK = "checkScalafix"
    private static final String RULES_PROPERTY = "scalafix.rules"

    @Override
    void apply(Project project) {
        def extension = project.extensions.create(EXTENSION, ScalafixExtension, project)
        def customRulesConfiguration = project.configurations.create(CUSTOM_RULES_CONFIGURATION)
        customRulesConfiguration.description = "Dependencies containing custom Scalafix rules"

        // TODO: fail if scala plugin is not applied
        project.plugins.withType(ScalaPlugin) {
            configureTasks(project, extension)

            project.afterEvaluate {
                if (extension.enableSemanticdb) {
                    configureSemanticdbCompilerPlugin(project)
                }
            }
        }
    }

    private void configureTasks(Project project, ScalafixExtension extension) {
        def fixTask = project.tasks.create(FIX_TASK)
        fixTask.group = TASK_GROUP
        fixTask.description = 'Runs Scalafix on Scala sources'

        def checkTask = project.tasks.create(CHECK_TASK)
        checkTask.group = TASK_GROUP
        checkTask.description = 'Fails if running Scalafix produces a diff or a linter error message'
        project.tasks.check.dependsOn(checkTask)

        project.sourceSets.each { SourceSet sourceSet ->
            configureTaskForSourceSet(sourceSet, false, fixTask, project, extension)
            configureTaskForSourceSet(sourceSet, true, checkTask, project, extension)
        }
    }

    private void configureTaskForSourceSet(SourceSet sourceSet,
                                           boolean checkOnly,
                                           Task mainTask,
                                           Project project,
                                           ScalafixExtension extension) {
        def name = mainTask.name + sourceSet.name.capitalize()
        def task = project.tasks.create(name, ScalafixTask, checkOnly)
        task.description = "${mainTask.description} in '${sourceSet.getName()}'"
        task.group = mainTask.group
        task.source = sourceSet.allScala.matching {
            // This is applied after evaluating the project
            include(extension.includes.get())
            exclude(extension.excludes.get())
        }
        task.configFile = extension.configFile
        task.rules.set(project.provider({
            String prop = project.findProperty(RULES_PROPERTY) ?: ""
            prop.split('\\s*,\\s*').findAll { !it.isEmpty() }.toList()
        }))
        mainTask.dependsOn += task
        project.afterEvaluate {
            if (extension.enableSemanticdb) {
                task.dependsOn project.tasks.getByName(sourceSet.getCompileTaskName('scala'))
            }
        }
    }

    private void configureSemanticdbCompilerPlugin(Project project) {
        def dependency = project.dependencies.create(BuildInfo.semanticdbArtifact)
        def configuration = project.configurations.detachedConfiguration(dependency)
        def compilerParameters = [
                "-Xplugin:" + configuration.asPath,
                "-P:semanticdb:sourceroot:" + project.projectDir,
                "-Yrangepos"
        ]

        project.tasks.withType(ScalaCompile) { ScalaCompile task ->
            task.scalaCompileOptions.additionalParameters =
                    (task.scalaCompileOptions.additionalParameters ?: []) + compilerParameters
        }
    }
}
