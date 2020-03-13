/*
 * Copyright (c) Microsoft Corporation
 *
 * All rights reserved.
 *
 * MIT License
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and
 * to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED *AS IS*, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
 * THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
 * TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.microsoft.azure.hdinsight.spark.console

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.ConsoleViewContentType.LOG_ERROR_OUTPUT
import com.microsoft.azure.hdinsight.common.MessageInfoType
import com.microsoft.azure.hdinsight.common.print
import com.microsoft.azure.hdinsight.sdk.common.livy.interactive.Session
import com.microsoft.intellij.rxjava.IdeaSchedulers
import org.jetbrains.plugins.scala.console.ScalaLanguageConsole
import rx.subjects.PublishSubject
import java.util.AbstractMap

class SparkScalaLivyConsoleRunProfileState(
        private val consoleBuilder: SparkScalaConsoleBuilder,
        val session: Session,
        private val logSubject: PublishSubject<AbstractMap.SimpleImmutableEntry<MessageInfoType, String>>
): RunProfileState {
    private val postStartCodes = """
        val __welcome = List(
            "Spark context available as 'sc' (master = " + sc.master + ", app id = " + sc.getConf.getAppId + ").",
            "Spark session available as 'spark'.",
            "Spark Version: " + sc.version,
            util.Properties.versionMsg
        ).mkString("\n")

        println(__welcome)
    """.trimIndent()

    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult? {
        val console = consoleBuilder.console
        val progressBarScheduler = IdeaSchedulers(consoleBuilder.project)
        val livySessionProcess = SparkLivySessionProcess(progressBarScheduler, session)
        val livySessionProcessHandler = SparkLivySessionProcessHandler(livySessionProcess)

        console.attachToProcess(livySessionProcessHandler)
        logSubject.subscribe({ typedMessage -> console.print(typedMessage) }, { err ->
            console.print("Livy interactive session is stop due to the error ${err.message}", LOG_ERROR_OUTPUT)
        })

        livySessionProcess
                .start()
                .subscribe({
                    livySessionProcessHandler.execute(postStartCodes)
                    (console as? ScalaLanguageConsole)?.apply {
                        // Customize the Spark Livy interactive console
                        prompt = "\nSpark>"
                    }
                }, { ex ->
                    console.print("Can't start Livy interactive session: ${ex.message}\n", LOG_ERROR_OUTPUT)
                })

        return DefaultExecutionResult(console, livySessionProcessHandler)
    }
}