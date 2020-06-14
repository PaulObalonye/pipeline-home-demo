// vars/performanceMemory.groovy

import java.lang.management.ManagementFactory

def call(mode) {

    def timestamp
    def masterPid

    pipeline {   
        agent {
            label "master"
        }
        environment {
            MODE = "${mode}"
        }
        stages {
                stage("Prepare") {
                    steps {
                        deleteDir()
                        script {
                            timestamp = sh(script: "date +%d-%b-%Y_%H-%M-%S", returnStdout: true).trim()
                            def master_name_array = ManagementFactory.getRuntimeMXBean().getName().split("@");
                            masterPid = master_name_array[0]
                        }
                    }
                }
                stage("Collect data") {
                    stages {
                        stage("VM Description"){
                            environment {
                                OUTPUT = "VM_description.txt"
                            }
                            steps{
                                dir ("memory"){
                                    sh """
                                    echo '==========\nVM.version\n==========\n\n' >> $OUTPUT
                                    jcmd $masterPid VM.version >> $OUTPUT
                                    echo '==========\nVM.system_properties\n==========\n\n' >> $OUTPUT
                                    jcmd $masterPid VM.system_properties >> $OUTPUT
                                    echo '==========\nVM.flags\n==========\n\n' >> $OUTPUT
                                    jcmd $masterPid VM.flags >> $OUTPUT
                                    """
                                    }
                                }
                            }
                        stage("VM Heap Dump"){
                            stages {
                                stage("via script") {
                                    // Ref: https://support.cloudbees.com/hc/en-us/articles/115001122568
                                    when {
                                        environment name: 'MODE', value: '1'
                                    }
                                    steps {
                                        dir ("memory"){
                                            sh """
                                            export JENKINS_JMAP_OUTPUT_DIR=\$(pwd)
                                            curl https://cloudbees-jenkins-scripts.s3.amazonaws.com/e206a5-linux/jenkinsmemory.sh > jenkinsmemory.sh
                                            chmod +x jenkinsmemory.sh
                                            ./jenkinsmemory.sh $masterPid 1
                                            """
                                        }
                                    }
                                }
                                stage("via jdk") {
                                    // Ref: https://support.cloudbees.com/hc/en-us/articles/222167128
                                    when {
                                        environment name: 'MODE', value: '2'
                                    }
                                    environment {
                                        OUTPUT_HEAPDUMP = "dump.hprof"
                                        OUTPUT_HISTOGRAM = "class_histogram.txt"
                                    }
                                    steps{
                                        dir ("memory"){
                                            script {
                                                // jcmd $masterPid GC.heap_dump filename=$OUTPUT_HEAPDUMP > Permission issue
                                                sh """
                                                jmap -dump:live,format=b,file=$OUTPUT_HEAPDUMP $masterPid
                                                jcmd $masterPid GC.class_histogram > $OUTPUT_HISTOGRAM
                                                """
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            post {
                success {
                    zip zipFile: "Memory_Data-${timestamp}.zip", archive: true, dir: "memory"
                }
            }
        }
}