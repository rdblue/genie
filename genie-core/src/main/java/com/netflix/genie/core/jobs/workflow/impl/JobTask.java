/*
 *
 *  Copyright 2015 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.netflix.genie.core.jobs.workflow.impl;

import com.netflix.genie.common.exceptions.GenieException;
import com.netflix.genie.common.exceptions.GeniePreconditionException;
import com.netflix.genie.core.jobs.JobConstants;
import com.netflix.genie.core.jobs.JobExecutionEnvironment;
import com.netflix.genie.core.services.AttachmentService;
import com.netflix.genie.core.services.impl.GenieFileTransferService;
import com.netflix.spectator.api.Registry;
import com.netflix.spectator.api.Timer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import javax.validation.constraints.NotNull;
import java.io.IOException;
import java.io.Writer;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of the workflow task for processing job information for genie mode.
 *
 * @author amsharma
 * @since 3.0.0
 */
@Slf4j
public class JobTask extends GenieBaseTask {

    private final AttachmentService attachmentService;
    private final Timer timer;
    private final GenieFileTransferService fts;

    /**
     * Constructor.
     *
     * @param attachmentService An implementation of the Attachment Service
     * @param registry          The metrics registry to use
     * @param fts               File transfer service
     * @throws GenieException If there is any problem.
     */
    public JobTask(
        @NotNull
        final AttachmentService attachmentService,
        @NotNull
        final Registry registry,
        @NotNull
        final GenieFileTransferService fts
    ) throws GenieException {
        this.attachmentService = attachmentService;
        this.timer = registry.timer("genie.jobs.tasks.jobTask.timer");
        this.fts = fts;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void executeTask(@NotNull final Map<String, Object> context) throws GenieException, IOException {
        final long start = System.nanoTime();
        try {
            final JobExecutionEnvironment jobExecEnv
                = (JobExecutionEnvironment) context.get(JobConstants.JOB_EXECUTION_ENV_KEY);
            final String jobWorkingDirectory = jobExecEnv.getJobWorkingDir().getCanonicalPath();
            final Writer writer = (Writer) context.get(JobConstants.WRITER_KEY);
            final String jobId = jobExecEnv
                .getJobRequest()
                .getId()
                .orElseThrow(() -> new GeniePreconditionException("No job id found. Unable to continue"));
            log.info("Starting Job Task for job {}", jobId);

            final Optional<String> setupFile = jobExecEnv.getJobRequest().getSetupFile();
            if (setupFile.isPresent()) {
                final String jobSetupFile = setupFile.get();
                if (StringUtils.isNotBlank(jobSetupFile)) {
                    final String localPath =
                        jobWorkingDirectory
                            + JobConstants.FILE_PATH_DELIMITER
                            + jobSetupFile.substring(jobSetupFile.lastIndexOf(JobConstants.FILE_PATH_DELIMITER) + 1);

                    fts.getFile(jobSetupFile, localPath);

                    writer.write("# Sourcing setup file specified in job request" + System.lineSeparator());
                    writer.write(
                        JobConstants.SOURCE
                            + localPath.replace(jobWorkingDirectory, "${" + JobConstants.GENIE_JOB_DIR_ENV_VAR + "}")
                            + System.lineSeparator());

                    // Append new line
                    writer.write(System.lineSeparator());
                }
            }

            // Iterate over and get all dependencies
            for (final String dependencyFile : jobExecEnv.getJobRequest().getDependencies()) {
                if (StringUtils.isNotBlank(dependencyFile)) {
                    final String localPath = jobWorkingDirectory
                        + JobConstants.FILE_PATH_DELIMITER
                        + dependencyFile.substring(dependencyFile.lastIndexOf(JobConstants.FILE_PATH_DELIMITER) + 1);

                    fts.getFile(dependencyFile, localPath);
                }
            }

            // Copy down the attachments if any to the current working directory
            this.attachmentService.copy(
                jobId,
                jobExecEnv.getJobWorkingDir());
            // Delete the files from the attachment service to save space on disk
            this.attachmentService.delete(jobId);

            // Print out the current Envrionment to a env file before running the command.
            writer.write("# Dump the environment to a env.log file" + System.lineSeparator());
            writer.write("env | sort > " + "${"
                + JobConstants.GENIE_JOB_DIR_ENV_VAR + "}"
                + JobConstants.GENIE_ENV_PATH
                + System.lineSeparator());

            // Append new line
            writer.write(System.lineSeparator());

            writer.write("# Kick off the command in background mode and wait for it using its pid"
                + System.lineSeparator());

            writer.write(
                jobExecEnv.getCommand().getExecutable()
                    + JobConstants.WHITE_SPACE
                    + jobExecEnv.getJobRequest().getCommandArgs()
                    + JobConstants.STDOUT_REDIRECT
                    + JobConstants.STDOUT_LOG_FILE_NAME
                    + JobConstants.STDERR_REDIRECT
                    + JobConstants.STDERR_LOG_FILE_NAME
                    + " &"
                    + System.lineSeparator()
            );

            // Wait for the above process started in background mode. Wait lets us get interrupted by kill signals.
            writer.write("wait $!" + System.lineSeparator());

            // Append new line
            writer.write(System.lineSeparator());

            // capture exit code and write to genie.done file
            writer.write("# Write the return code from the command in the done file." + System.lineSeparator());
            writer.write(JobConstants.GENIE_DONE_FILE_CONTENT_PREFIX
                + JobConstants.GENIE_DONE_FILE_NAME
                + System.lineSeparator());

            // Print the timestamp once its done running.
            writer.write("echo End: `date '+%Y-%m-%d %H:%M:%S'`\n");

            log.info("Finished Job Task for job {}", jobExecEnv.getJobRequest().getId());
        } finally {
            final long finish = System.nanoTime();
            this.timer.record(finish - start, TimeUnit.NANOSECONDS);
        }
    }
}
