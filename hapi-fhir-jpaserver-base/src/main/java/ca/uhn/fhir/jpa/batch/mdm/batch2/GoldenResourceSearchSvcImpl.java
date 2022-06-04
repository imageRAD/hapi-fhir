package ca.uhn.fhir.jpa.batch.mdm.batch2;

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

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.svc.IBatchIdChunk;
import ca.uhn.fhir.jpa.api.svc.IGoldenResourceSearchSvc;
import ca.uhn.fhir.jpa.partition.SystemRequestDetails;
import ca.uhn.fhir.jpa.searchparam.MatchUrlService;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.mdm.api.MdmConstants;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.SortOrderEnum;
import ca.uhn.fhir.rest.api.SortSpec;
import ca.uhn.fhir.rest.api.server.storage.ResourcePersistentId;
import ca.uhn.fhir.rest.param.DateRangeParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.util.DateRangeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

public class GoldenResourceSearchSvcImpl implements IGoldenResourceSearchSvc {
	@Autowired
	private MatchUrlService myMatchUrlService;

	@Autowired
	private DaoRegistry myDaoRegistry;

	@Autowired
	private FhirContext myFhirContext;

	@Override
	@Transactional
	public IBatchIdChunk fetchGoldenResourceIdsPage(Date theStart, Date theEnd, @Nullable RequestPartitionId theRequestPartitionId, @Nonnull String theResourceType) {

		int pageSize = 20000;
		return fetchResourceIdsPageWithResourceType(theStart, theEnd, pageSize, theResourceType, theRequestPartitionId);
	}

	private IBatchIdChunk fetchResourceIdsPageWithResourceType(Date theStart, Date theEnd, int thePageSize, String theResourceType, RequestPartitionId theRequestPartitionId) {

		RuntimeResourceDefinition def = myFhirContext.getResourceDefinition(theResourceType);

		SearchParameterMap searchParamMap = myMatchUrlService.translateMatchUrl(theResourceType, def);
		searchParamMap.setSort(new SortSpec(Constants.PARAM_LASTUPDATED, SortOrderEnum.ASC));
		DateRangeParam chunkDateRange = DateRangeUtil.narrowDateRange(searchParamMap.getLastUpdated(), theStart, theEnd);
		searchParamMap.setLastUpdated(chunkDateRange);
		searchParamMap.setCount(thePageSize);
		searchParamMap.add("_tag", new TokenParam(MdmConstants.SYSTEM_GOLDEN_RECORD_STATUS, MdmConstants.CODE_GOLDEN_RECORD));

		IFhirResourceDao<?> dao = myDaoRegistry.getResourceDao(theResourceType);
		SystemRequestDetails request = new SystemRequestDetails();
		request.setRequestPartitionId(theRequestPartitionId);
		List<ResourcePersistentId> ids = dao.searchForIds(searchParamMap, request);

		// just a list of the same size where every element is the same resource type
		List<String> resourceTypes = ids
			.stream()
			.map(t -> theResourceType)
			.collect(Collectors.toList());

		Date lastDate = null;
		if (ids.size() > 0) {
			lastDate = dao.readByPid(ids.get(ids.size() - 1)).getMeta().getLastUpdated();
		}

		return new IBatchIdChunk(ids, resourceTypes, lastDate);
	}
}
