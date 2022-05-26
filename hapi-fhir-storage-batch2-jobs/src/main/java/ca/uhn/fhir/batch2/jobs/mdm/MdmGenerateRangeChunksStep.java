package ca.uhn.fhir.batch2.jobs.mdm;

/*-
 * #%L
 * hapi-fhir-storage-batch2-jobs
 * %%
 * Copyright (C) 2014 - 2022 Smile CDR, Inc.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import ca.uhn.fhir.batch2.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.Date;

import static ca.uhn.fhir.batch2.jobs.Batch2Constants.BATCH_START_DATE;

public class MdmGenerateRangeChunksStep implements IFirstJobStepWorker<MdmJobParameters, MdmChunkRange> {
	private static final Logger ourLog = LoggerFactory.getLogger(MdmGenerateRangeChunksStep.class);

	@Nonnull
	@Override
	public RunOutcome run(@Nonnull StepExecutionDetails<MdmJobParameters, VoidModel> theStepExecutionDetails, @Nonnull IJobDataSink<MdmChunkRange> theDataSink) throws JobExecutionFailedException {
		MdmJobParameters params = theStepExecutionDetails.getParameters();

		Date start = BATCH_START_DATE;
		Date end = new Date();

		for (String nextResourceType : params.getResourceType()) {
			ourLog.info("Initiating mdm clear of [{}]] Golden Resources from {} to {}", nextResourceType, start, end);
			MdmChunkRange nextRange = new MdmChunkRange();
			nextRange.setResourceType(nextResourceType);
			nextRange.setStart(start);
			nextRange.setEnd(end);
			theDataSink.accept(nextRange);
		}

		return RunOutcome.SUCCESS;
	}

}
