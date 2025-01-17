/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.gradle.test

import org.apache.tools.ant.DefaultLogger
import org.apache.tools.ant.taskdefs.condition.Os
import org.elasticsearch.gradle.VersionProperties
import org.gradle.api.*
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Exec

import java.nio.file.Paths

/**
 * A helper for creating tasks to build a cluster that is used by a task, and tear down the cluster when the task is finished.
 */
class ClusterFormationTasks {

    static class NodeInfo {
        /** common configuration for all nodes, including this one */
        ClusterConfiguration config
        /** node number within the cluster, for creating unique names and paths */
        int nodeNum
        /** name of the cluster this node is part of */
        String clusterName
        /** root directory all node files and operations happen under */
        File baseDir
        /** the pid file the node will use */
        File pidFile
        /** elasticsearch home dir */
        File homeDir
        /** working directory for the node process */
        File cwd
        /** file that if it exists, indicates the node failed to start */
        File failedMarker
        /** stdout/stderr log of the elasticsearch process for this node */
        File startLog
        /** directory to install plugins from */
        File pluginsTmpDir
        /** environment variables to start the node with */
        Map<String, String> env
        /** arguments to start the node with */
        List<String> args
        /** Path to the elasticsearch start script */
        String esScript
        /** buffer for ant output when starting this node */
        ByteArrayOutputStream buffer = new ByteArrayOutputStream()

        /** Creates a node to run as part of a cluster for the given task */
        NodeInfo(ClusterConfiguration config, int nodeNum, Project project, Task task) {
            this.config = config
            this.nodeNum = nodeNum
            clusterName = "${task.path.replace(':', '_').substring(1)}"
            baseDir = new File(project.buildDir, "cluster/${task.name} node${nodeNum}")
            pidFile = new File(baseDir, 'es.pid')
            homeDir = homeDir(baseDir, config.distribution)
            cwd = new File(baseDir, "cwd")
            failedMarker = new File(cwd, 'run.failed')
            startLog = new File(cwd, 'run.log')
            pluginsTmpDir = new File(baseDir, "plugins tmp")

            env = [
                'JAVA_HOME' : project.javaHome,
                'ES_GC_OPTS': config.jvmArgs // we pass these with the undocumented gc opts so the argline can set gc, etc
            ]
            args = config.systemProperties.collect { key, value -> "-D${key}=${value}" }
            for (Map.Entry<String, String> property : System.properties.entrySet()) {
                if (property.getKey().startsWith('es.')) {
                    args.add("-D${property.getKey()}=${property.getValue()}")
                }
            }
            // running with cmd on windows will look for this with the .bat extension
            esScript = new File(homeDir, 'bin/elasticsearch').toString()
        }

        /** Returns debug string for the command that started this node. */
        String getCommandString() {
            String esCommandString = "Elasticsearch node ${nodeNum} command: ${esScript} "
            esCommandString += args.join(' ')
            esCommandString += '\nenvironment:'
            env.each { k, v -> esCommandString += "\n  ${k}: ${v}" }
            return esCommandString
        }

        /** Returns the directory elasticsearch home is contained in for the given distribution */
        static File homeDir(File baseDir, String distro) {
            String path
            switch (distro) {
                case 'zip':
                case 'tar':
                    path = "elasticsearch-${VersionProperties.elasticsearch}"
                    break;
                default:
                    throw new InvalidUserDataException("Unknown distribution: ${distro}")
            }
            return new File(baseDir, path)
        }
    }

    /**
     * Adds dependent tasks to the given task to start and stop a cluster with the given configuration.
     */
    static void setup(Project project, Task task, ClusterConfiguration config) {
        if (task.getEnabled() == false) {
            // no need to add cluster formation tasks if the task won't run!
            return
        }
        configureDistributionDependency(project, config.distribution)
        List<Task> startTasks = []
        List<NodeInfo> nodes = []
        for (int i = 0; i < config.numNodes; ++i) {
            NodeInfo node = new NodeInfo(config, i, project, task)
            nodes.add(node)
            startTasks.add(configureNode(project, task, node))
        }

        Task wait = configureWaitTask("${task.name}#wait", project, nodes, startTasks)
        task.dependsOn(wait)
    }

    /** Adds a dependency on the given distribution */
    static void configureDistributionDependency(Project project, String distro) {
        String elasticsearchVersion = VersionProperties.elasticsearch
        String packaging = distro == 'tar' ? 'tar.gz' : distro
        project.configurations {
            elasticsearchDistro
        }
        project.dependencies {
            elasticsearchDistro "org.elasticsearch.distribution.${distro}:elasticsearch:${elasticsearchVersion}@${packaging}"
        }
    }

    /**
     * Adds dependent tasks to start an elasticsearch cluster before the given task is executed,
     * and stop it after it has finished executing.
     *
     * The setup of the cluster involves the following:
     * <ol>
     *   <li>Cleanup the extraction directory</li>
     *   <li>Extract a fresh copy of elasticsearch</li>
     *   <li>Write an elasticsearch.yml config file</li>
     *   <li>Copy plugins that will be installed to a temporary dir (which contains spaces)</li>
     *   <li>Install plugins</li>
     *   <li>Run additional setup commands</li>
     *   <li>Start elasticsearch<li>
     * </ol>
     *
     * @return a task which starts the node.
     */
    static Task configureNode(Project project, Task task, NodeInfo node) {

        // tasks are chained so their execution order is maintained
        Task setup = project.tasks.create(name: taskName(task, node, 'clean'), type: Delete, dependsOn: task.dependsOn.collect()) {
            delete node.homeDir
            delete node.cwd
            doLast {
                node.cwd.mkdirs()
            }
        }
        setup = configureCheckPreviousTask(taskName(task, node, 'checkPrevious'), project, setup, node)
        setup = configureStopTask(taskName(task, node, 'stopPrevious'), project, setup, node)
        setup = configureExtractTask(taskName(task, node, 'extract'), project, setup, node)
        setup = configureWriteConfigTask(taskName(task, node, 'configure'), project, setup, node)
        setup = configureCopyPluginsTask(taskName(task, node, 'copyPlugins'), project, setup, node)

        // install plugins
        for (Map.Entry<String, FileCollection> plugin : node.config.plugins.entrySet()) {
            // replace every dash followed by a character with just the uppercase character
            String camelName = plugin.getKey().replaceAll(/-(\w)/) { _, c -> c.toUpperCase(Locale.ROOT) }
            String actionName = "install${camelName[0].toUpperCase(Locale.ROOT) + camelName.substring(1)}Plugin"
            // delay reading the file location until execution time by wrapping in a closure within a GString
            String file = "${-> new File(node.pluginsTmpDir, plugin.getValue().singleFile.getName()).toURI().toURL().toString()}"
            Object[] args = [new File(node.homeDir, 'bin/plugin'), 'install', file]
            setup = configureExecTask(taskName(task, node, actionName), project, setup, node, args)
        }

        // extra setup commands
        for (Map.Entry<String, Object[]> command : node.config.setupCommands.entrySet()) {
            setup = configureExecTask(taskName(task, node, command.getKey()), project, setup, node, command.getValue())
        }

        Task start = configureStartTask(taskName(task, node, 'start'), project, setup, node)

        if (node.config.daemonize) {
            // if we are running in the background, make sure to stop the server when the task completes
            Task stop = configureStopTask(taskName(task, node, 'stop'), project, [], node)
            task.finalizedBy(stop)
        }
        return start
    }

    /** Adds a task to extract the elasticsearch distribution */
    static Task configureExtractTask(String name, Project project, Task setup, NodeInfo node) {
        List extractDependsOn = [project.configurations.elasticsearchDistro, setup]
        Task extract
        switch (node.config.distribution) {
            case 'zip':
                extract = project.tasks.create(name: name, type: Copy, dependsOn: extractDependsOn) {
                    from { project.zipTree(project.configurations.elasticsearchDistro.singleFile) }
                    into node.baseDir
                }
                break;
            case 'tar':
                extract = project.tasks.create(name: name, type: Copy, dependsOn: extractDependsOn) {
                    from {
                        project.tarTree(project.resources.gzip(project.configurations.elasticsearchDistro.singleFile))
                    }
                    into node.baseDir
                }
                break;
            default:
                throw new InvalidUserDataException("Unknown distribution: ${node.config.distribution}")
        }
        return extract
    }

    /** Adds a task to write elasticsearch.yml for the given node configuration */
    static Task configureWriteConfigTask(String name, Project project, Task setup, NodeInfo node) {
        Map esConfig = [
            'cluster.name'                    : node.clusterName,
            'http.port'                       : node.config.httpPort + node.nodeNum,
            'transport.tcp.port'              : node.config.transportPort + node.nodeNum,
            'pidfile'                         : node.pidFile,
            'discovery.zen.ping.unicast.hosts': (0..<node.config.numNodes).collect{"127.0.0.1:${node.config.transportPort + it}"}.join(','),
            'path.repo'                       : "${node.homeDir}/repo",
            'path.shared_data'                : "${node.homeDir}/../",
            // Define a node attribute so we can test that it exists
            'node.testattr'                   : 'test',
            'repositories.url.allowed_urls'   : 'http://snapshot.test*'
        ]

        return project.tasks.create(name: name, type: DefaultTask, dependsOn: setup) << {
            File configFile = new File(node.homeDir, 'config/elasticsearch.yml')
            logger.info("Configuring ${configFile}")
            configFile.setText(esConfig.collect { key, value -> "${key}: ${value}" }.join('\n'), 'UTF-8')
        }
    }

    /** Adds a task to copy plugins to a temp dir, which they will later be installed from. */
    static Task configureCopyPluginsTask(String name, Project project, Task setup, NodeInfo node) {
        if (node.config.plugins.isEmpty()) {
            return setup
        }

        return project.tasks.create(name: name, type: Copy, dependsOn: setup) {
            into node.pluginsTmpDir
            from(node.config.plugins.values())
        }
    }

    /** Adds a task to execute a command to help setup the cluster */
    static Task configureExecTask(String name, Project project, Task setup, NodeInfo node, Object[] execArgs) {
        return project.tasks.create(name: name, type: Exec, dependsOn: setup) {
            workingDir node.cwd
            if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                executable 'cmd'
                args '/C', 'call'
            } else {
                executable 'sh'
            }
            args execArgs
            // only show output on failure, when not in info or debug mode
            if (logger.isInfoEnabled() == false) {
                standardOutput = new ByteArrayOutputStream()
                errorOutput = standardOutput
                ignoreExitValue = true
                doLast {
                    if (execResult.exitValue != 0) {
                        logger.error(standardOutput.toString())
                        throw new GradleException("Process '${execArgs.join(' ')}' finished with non-zero exit value ${execResult.exitValue}")
                    }
                }
            }
        }
    }

    /** Adds a task to start an elasticsearch node with the given configuration */
    static Task configureStartTask(String name, Project project, Task setup, NodeInfo node) {
        String executable
        List<String> esArgs = []
        if (Os.isFamily(Os.FAMILY_WINDOWS)) {
            executable = 'cmd'
            esArgs.add('/C')
            esArgs.add('call')
        } else {
            executable = 'sh'
        }

        // this closure is converted into ant nodes by groovy's AntBuilder
        Closure antRunner = {
            // we must add debug options inside the closure so the config is read at execution time, as
            // gradle task options are not processed until the end of the configuration phase
            if (node.config.debug) {
                println 'Running elasticsearch in debug mode, suspending until connected on port 8000'
                node.env['JAVA_OPTS'] = '-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=8000'
            }

            // Due to how ant exec works with the spawn option, we lose all stdout/stderr from the
            // process executed. To work around this, when spawning, we wrap the elasticsearch start
            // command inside another shell script, which simply internally redirects the output
            // of the real elasticsearch script. This allows ant to keep the streams open with the
            // dummy process, but us to have the output available if there is an error in the
            // elasticsearch start script
            String script = node.esScript
            if (node.config.daemonize) {
                String scriptName = 'run'
                String argsPasser = '"$@"'
                String exitMarker = "; if [ \$? != 0 ]; then touch run.failed; fi"
                if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                    scriptName += '.bat'
                    argsPasser = '%*'
                    exitMarker = "\r\n if \"%errorlevel%\" neq \"0\" ( type nul >> run.failed )"
                }
                File wrapperScript = new File(node.cwd, scriptName)
                wrapperScript.setText("\"${script}\" ${argsPasser} > run.log 2>&1 ${exitMarker}", 'UTF-8')
                script = wrapperScript.toString()
            }

            exec(executable: executable, spawn: node.config.daemonize, dir: node.cwd, taskname: 'elasticsearch') {
                node.env.each { key, value -> env(key: key, value: value) }
                arg(value: script)
                node.args.each { arg(value: it) }
            }

        }

        // this closure is the actual code to run elasticsearch
        Closure elasticsearchRunner = {
            node.getCommandString().eachLine { line -> logger.info(line) }

            if (logger.isInfoEnabled() || node.config.daemonize == false) {
                // run with piping streams directly out (even stderr to stdout since gradle would capture it)
                runAntCommand(project, antRunner, System.out, System.err)
            } else {
                // buffer the output, we may not need to print it
                PrintStream captureStream = new PrintStream(node.buffer, true, "UTF-8")
                runAntCommand(project, antRunner, captureStream, captureStream)
            }
        }

        Task start = project.tasks.create(name: name, type: DefaultTask, dependsOn: setup)
        start.doLast(elasticsearchRunner)
        return start
    }

    static Task configureWaitTask(String name, Project project, List<NodeInfo> nodes, List<Task> startTasks) {
        Task wait = project.tasks.create(name: name, dependsOn: startTasks)
        wait.doLast {
            ant.waitfor(maxwait: '30', maxwaitunit: 'second', checkevery: '500', checkeveryunit: 'millisecond', timeoutproperty: "failed${name}") {
                or {
                    for (NodeInfo node : nodes) {
                        resourceexists {
                            file(file: node.failedMarker.toString())
                        }
                    }
                    and {
                        for (NodeInfo node : nodes) {
                            resourceexists {
                                file(file: node.pidFile.toString())
                            }
                            http(url: "http://localhost:${node.config.httpPort + node.nodeNum}")
                        }
                    }
                }
            }
            boolean anyNodeFailed = false
            for (NodeInfo node : nodes) {
                anyNodeFailed |= node.failedMarker.exists()
            }
            if (ant.properties.containsKey("failed${name}".toString()) || anyNodeFailed) {
                for (NodeInfo node : nodes) {
                    if (logger.isInfoEnabled() == false) {
                        // We already log the command at info level. No need to do it twice.
                        node.getCommandString().eachLine { line -> logger.error(line) }
                    }
                    // the waitfor failed, so dump any output we got (may be empty if info logging, but that is ok)
                    logger.error("Node ${node.nodeNum} ant output:")
                    node.buffer.toString('UTF-8').eachLine { line -> logger.error(line) }
                    // also dump the log file for the startup script (which will include ES logging output to stdout)
                    if (node.startLog.exists()) {
                        logger.error("Node ${node.nodeNum} log:")
                        node.startLog.eachLine { line -> logger.error(line) }
                    }
                }
                throw new GradleException('Failed to start elasticsearch')
            }
        }
        return wait
    }

    /** Adds a task to check if the process with the given pidfile is actually elasticsearch */
    static Task configureCheckPreviousTask(String name, Project project, Object depends, NodeInfo node) {
        return project.tasks.create(name: name, type: Exec, dependsOn: depends) {
            onlyIf { node.pidFile.exists() }
            // the pid file won't actually be read until execution time, since the read is wrapped within an inner closure of the GString
            ext.pid = "${ -> node.pidFile.getText('UTF-8').trim()}"
            File jps
            if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                jps = getJpsExecutableByName(project, "jps.exe")
            } else {
                jps = getJpsExecutableByName(project, "jps")
            }
            if (!jps.exists()) {
                throw new GradleException("jps executable not found; ensure that you're running Gradle with the JDK rather than the JRE")
            }
            commandLine jps, '-l'
            standardOutput = new ByteArrayOutputStream()
            doLast {
                String out = standardOutput.toString()
                if (out.contains("${pid} org.elasticsearch.bootstrap.Elasticsearch") == false) {
                    logger.error('jps -l')
                    logger.error(out)
                    logger.error("pid file: ${pidFile}")
                    logger.error("pid: ${pid}")
                    throw new GradleException("jps -l did not report any process with org.elasticsearch.bootstrap.Elasticsearch\n" +
                            "Did you run gradle clean? Maybe an old pid file is still lying around.")
                } else {
                    logger.info(out)
                }
            }
        }
    }

    private static File getJpsExecutableByName(Project project, String jpsExecutableName) {
        return Paths.get(project.javaHome.toString(), "bin/" + jpsExecutableName).toFile()
    }

    /** Adds a task to kill an elasticsearch node with the given pidfile */
    static Task configureStopTask(String name, Project project, Object depends, NodeInfo node) {
        return project.tasks.create(name: name, type: Exec, dependsOn: depends) {
            onlyIf { node.pidFile.exists() }
            // the pid file won't actually be read until execution time, since the read is wrapped within an inner closure of the GString
            ext.pid = "${ -> node.pidFile.getText('UTF-8').trim()}"
            doFirst {
                logger.info("Shutting down external node with pid ${pid}")
            }
            if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                executable 'Taskkill'
                args '/PID', pid, '/F'
            } else {
                executable 'kill'
                args '-9', pid
            }
            doLast {
                project.delete(node.pidFile)
            }
        }
    }

    /** Returns a unique task name for this task and node configuration */
    static String taskName(Task parentTask, NodeInfo node, String action) {
        if (node.config.numNodes > 1) {
            return "${parentTask.name}#node${node.nodeNum}.${action}"
        } else {
            return "${parentTask.name}#${action}"
        }
    }

    /** Runs an ant command, sending output to the given out and error streams */
    static void runAntCommand(Project project, Closure command, PrintStream outputStream, PrintStream errorStream) {
        DefaultLogger listener = new DefaultLogger(
                errorPrintStream: errorStream,
                outputPrintStream: outputStream,
                messageOutputLevel: org.apache.tools.ant.Project.MSG_INFO)

        project.ant.project.addBuildListener(listener)
        project.configure(project.ant, command)
        project.ant.project.removeBuildListener(listener)
    }
}
