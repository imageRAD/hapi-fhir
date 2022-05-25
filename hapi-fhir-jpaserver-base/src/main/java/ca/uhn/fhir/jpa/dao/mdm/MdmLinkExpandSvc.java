package ca.uhn.fhir.jpa.dao.mdm;

/*-
 * #%L
 * HAPI FHIR JPA Server
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

import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.svc.IIdHelperService;
import ca.uhn.fhir.jpa.dao.data.IMdmLinkDao;
import ca.uhn.fhir.mdm.api.MdmMatchResultEnum;
import ca.uhn.fhir.mdm.log.Logs;
import ca.uhn.fhir.model.primitive.IdDt;
import ca.uhn.fhir.rest.api.server.storage.ResourcePersistentId;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class MdmLinkExpandSvc {
	private static final Logger ourLog = Logs.getMdmTroubleshootingLog();

	@Autowired
	private IMdmLinkDao myMdmLinkDao;
	@Autowired
	private IIdHelperService myIdHelperService;

	public MdmLinkExpandSvc() {
	}

	/**
	 * Given a source resource, perform MDM expansion and return all the resource IDs of all resources that are
	 * MDM-Matched to this resource.
	 *
	 * @param theResource The resource to MDM-Expand
	 * @return A set of strings representing the FHIR IDs of the expanded resources.
	 */
	public Set<String> expandMdmBySourceResource(IBaseResource theResource) {
		ourLog.debug("About to MDM-expand source resource {}", theResource);
		return expandMdmBySourceResourceId(theResource.getIdElement());
	}

	/**
	 *  Given a resource ID of a source resource, perform MDM expansion and return all the resource IDs of all resources that are
	 *  MDM-Matched to this resource.
	 *
	 * @param theId The Resource ID of the resource to MDM-Expand
	 * @return A set of strings representing the FHIR ids of the expanded resources.
	 */
	public Set<String> expandMdmBySourceResourceId(IIdType theId) {
		ourLog.debug("About to expand source resource with resource id {}", theId);
		Long pidOrThrowException = myIdHelperService.getPidOrThrowException(RequestPartitionId.allPartitions(), theId).getIdAsLong();
		return expandMdmBySourceResourcePid(pidOrThrowException);
	}

	/**
	 *  Given a PID of a source resource, perform MDM expansion and return all the resource IDs of all resources that are
	 *  MDM-Matched to this resource.
	 *
	 * @param theSourceResourcePid The PID of the resource to MDM-Expand
	 * @return A set of strings representing the FHIR ids of the expanded resources.
	 */
	public Set<String> expandMdmBySourceResourcePid(Long theSourceResourcePid) {
		ourLog.debug("About to expand source resource with PID {}", theSourceResourcePid);
		List<IMdmLinkDao.MdmPidTuple> goldenPidSourcePidTuples = myMdmLinkDao.expandPidsBySourcePidAndMatchResult(theSourceResourcePid, MdmMatchResultEnum.MATCH);
		return flattenPidTuplesToSet(theSourceResourcePid, goldenPidSourcePidTuples);
	}

	/**
	 *  Given a PID of a golden resource, perform MDM expansion and return all the resource IDs of all resources that are
	 *  MDM-Matched to this golden resource.
	 *
	 * @param theGoldenResourcePid The PID of the golden resource to MDM-Expand.
	 * @return A set of strings representing the FHIR ids of the expanded resources.
	 */
	public Set<String> expandMdmByGoldenResourceId(Long theGoldenResourcePid) {
		ourLog.debug("About to expand golden resource with PID {}", theGoldenResourcePid);
		List<IMdmLinkDao.MdmPidTuple> goldenPidSourcePidTuples = myMdmLinkDao.expandPidsByGoldenResourcePidAndMatchResult(theGoldenResourcePid, MdmMatchResultEnum.MATCH);
		return flattenPidTuplesToSet(theGoldenResourcePid, goldenPidSourcePidTuples);
	}


	/**
	 *  Given a resource ID of a golden resource, perform MDM expansion and return all the resource IDs of all resources that are
	 *  MDM-Matched to this golden resource.
	 *
	 * @param theGoldenResourcePid The resource ID of the golden resource to MDM-Expand.
	 * @return A set of strings representing the FHIR ids of the expanded resources.
	 */
	public Set<String> expandMdmByGoldenResourcePid(Long theGoldenResourcePid) {
		ourLog.debug("About to expand golden resource with PID {}", theGoldenResourcePid);
		List<IMdmLinkDao.MdmPidTuple> goldenPidSourcePidTuples = myMdmLinkDao.expandPidsByGoldenResourcePidAndMatchResult(theGoldenResourcePid, MdmMatchResultEnum.MATCH);
		return flattenPidTuplesToSet(theGoldenResourcePid, goldenPidSourcePidTuples);
	}

	public Set<String> expandMdmByGoldenResourceId(IdDt theId) {
		ourLog.debug("About to expand golden resource with golden resource id {}", theId);
		Long pidOrThrowException = myIdHelperService.getPidOrThrowException(RequestPartitionId.allPartitions(), theId).getIdAsLong();
		return expandMdmByGoldenResourcePid(pidOrThrowException);
	}

	@Nonnull
	private Set<String> flattenPidTuplesToSet(Long initialPid, List<IMdmLinkDao.MdmPidTuple> goldenPidSourcePidTuples) {
		Set<Long> flattenedPids = new HashSet<>();
		goldenPidSourcePidTuples.forEach(tuple -> {
			flattenedPids.add(tuple.getSourcePid());
			flattenedPids.add(tuple.getGoldenPid());
		});
		Set<ResourcePersistentId> pids  = flattenedPids.stream().map(t-> new ResourcePersistentId(t)).collect(Collectors.toSet());
		Set<String> resourceIds = myIdHelperService.translatePidsToFhirResourceIds(pids);
		ourLog.debug("Pid {} has been expanded to [{}]", initialPid, String.join(",", resourceIds));
		return resourceIds;
	}
}
