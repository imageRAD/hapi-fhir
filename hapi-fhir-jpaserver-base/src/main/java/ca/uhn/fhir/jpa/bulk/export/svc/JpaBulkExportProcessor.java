package ca.uhn.fhir.jpa.bulk.export.svc;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.i18n.Msg;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.config.DaoConfig;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.model.BulkExportJobInfo;
import ca.uhn.fhir.jpa.api.svc.IIdHelperService;
import ca.uhn.fhir.jpa.bulk.export.api.IBulkExportProcessor;
import ca.uhn.fhir.jpa.bulk.export.model.BulkExportJobStatusEnum;
import ca.uhn.fhir.jpa.bulk.export.model.ExportPIDIteratorParameters;
import ca.uhn.fhir.jpa.dao.IResultIterator;
import ca.uhn.fhir.jpa.dao.ISearchBuilder;
import ca.uhn.fhir.jpa.dao.SearchBuilderFactory;
import ca.uhn.fhir.jpa.dao.data.IBulkExportCollectionDao;
import ca.uhn.fhir.jpa.dao.data.IBulkExportJobDao;
import ca.uhn.fhir.jpa.dao.data.IMdmLinkDao;
import ca.uhn.fhir.jpa.dao.index.IJpaIdHelperService;
import ca.uhn.fhir.jpa.dao.mdm.MdmExpansionCacheSvc;
import ca.uhn.fhir.jpa.entity.BulkExportCollectionEntity;
import ca.uhn.fhir.jpa.entity.BulkExportCollectionFileEntity;
import ca.uhn.fhir.jpa.entity.BulkExportJobEntity;
import ca.uhn.fhir.jpa.model.search.SearchRuntimeDetails;
import ca.uhn.fhir.jpa.partition.SystemRequestDetails;
import ca.uhn.fhir.jpa.searchparam.MatchUrlService;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.util.QueryChunker;
import ca.uhn.fhir.mdm.api.MdmMatchResultEnum;
import ca.uhn.fhir.model.primitive.IdDt;
import ca.uhn.fhir.rest.api.server.bulk.BulkDataExportOptions;
import ca.uhn.fhir.rest.api.server.storage.ResourcePersistentId;
import ca.uhn.fhir.rest.param.DateRangeParam;
import ca.uhn.fhir.rest.param.ReferenceOrListParam;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.util.SearchParameterUtil;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.instance.model.api.IPrimitiveType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class JpaBulkExportProcessor implements IBulkExportProcessor {
	private static final Logger ourLog = LoggerFactory.getLogger(JpaBulkExportProcessor.class);
	public static final int QUERY_CHUNK_SIZE = 100;

	@Autowired
	private FhirContext myContext;

	@Autowired
	private DaoConfig myDaoConfig;

	@Autowired
	private MatchUrlService myMatchUrlService;

	@Autowired
	private DaoRegistry myDaoRegistry;

	@Autowired
	protected SearchBuilderFactory mySearchBuilderFactory;

	@Autowired
	private BulkExportDaoSvc myBulkExportDaoSvc;

	@Autowired
	private IBulkExportJobDao myBulkExportJobDao;

	@Autowired
	private IBulkExportCollectionDao myCollectionDao;

	private IIdHelperService myIdHelperService;
	@Autowired
	private IMdmLinkDao myMdmLinkDao;
	@Autowired
	private IJpaIdHelperService myJpaIdHelperService;
	@Autowired
	private MdmExpansionCacheSvc myMdmExpansionCacheSvc;

	private final HashMap<String, ISearchBuilder> myResourceTypeToSearchBuilder = new HashMap<>();

	@Override
	public Iterator<ResourcePersistentId> getResourcePidIterator(ExportPIDIteratorParameters theParams) {
		String resourceType = theParams.getResourceType();
		String jobId = theParams.getJobId();
		RuntimeResourceDefinition def = myContext.getResourceDefinition(resourceType);

		Set<ResourcePersistentId> pids = new HashSet<>();

		if (theParams.getExportStyle() == BulkDataExportOptions.ExportStyle.PATIENT) {
			// Patient
			if (myDaoConfig.getIndexMissingFields() == DaoConfig.IndexEnabledEnum.DISABLED) {
				String errorMessage = "You attempted to start a Patient Bulk Export, but the system has `Index Missing Fields` disabled. It must be enabled for Patient Bulk Export";
				ourLog.error(errorMessage);
				throw new IllegalStateException(Msg.code(797) + errorMessage);
			}

			List<SearchParameterMap> maps = createSearchParameterMapsForResourceType(def, theParams);
			String patientSearchParam = getPatientSearchParamForCurrentResourceType(theParams.getResourceType()).getName();

			for (SearchParameterMap map : maps) {
				//Ensure users did not monkey with the patient compartment search parameter.
				validateSearchParametersForPatient(map, theParams);

				ISearchBuilder searchBuilder = getSearchBuilderForLocalResourceType(theParams);

				if (!resourceType.equalsIgnoreCase("Patient")) {
					map.add(patientSearchParam, new ReferenceParam().setMissing(false));
				}

				IResultIterator resultIterator = searchBuilder.createQuery(map,
					new SearchRuntimeDetails(null, jobId),
					null,
					RequestPartitionId.allPartitions());
				while (resultIterator.hasNext()) {
					pids.add(resultIterator.next());
				}
			}
		}
		else if (theParams.getExportStyle() == BulkDataExportOptions.ExportStyle.GROUP) {
			// Group
			if (resourceType.equalsIgnoreCase("Patient")) {
				return getExpandedPatientIterator(theParams);
			}

			Set<String> expandedMemberResourceIds = expandAllPatientPidsFromGroup(theParams);
			if (ourLog.isDebugEnabled()) {
				ourLog.debug("Group/{} has been expanded to members:[{}]", theParams, String.join(",", expandedMemberResourceIds));
			}

			//Next, let's search for the target resources, with their correct patient references, chunked.
			//The results will be jammed into myReadPids
			QueryChunker<String> queryChunker = new QueryChunker<>();
			queryChunker.chunk(new ArrayList<>(expandedMemberResourceIds), QUERY_CHUNK_SIZE, (idChunk) -> {
				queryResourceTypeWithReferencesToPatients(pids, idChunk, theParams, def);
			});
		}
		else {
			// System
			List<SearchParameterMap> maps = createSearchParameterMapsForResourceType(def, theParams);
			ISearchBuilder searchBuilder = getSearchBuilderForLocalResourceType(theParams);

			for (SearchParameterMap map : maps) {
				IResultIterator resultIterator = searchBuilder.createQuery(map,
					new SearchRuntimeDetails(null, jobId),
					null,
					RequestPartitionId.allPartitions());
				while (resultIterator.hasNext()) {
					pids.add(resultIterator.next());
				}
			}
		}

		return pids.iterator();
	}

	/**
	 * Get and cache an ISearchBuilder for the given resource type this partition is responsible for.
	 */
	protected ISearchBuilder getSearchBuilderForLocalResourceType(ExportPIDIteratorParameters theParams) {
		String resourceType = theParams.getResourceType();
		if (!myResourceTypeToSearchBuilder.containsKey(resourceType)) {
			IFhirResourceDao<?> dao = myDaoRegistry.getResourceDao(resourceType);
			RuntimeResourceDefinition def = myContext.getResourceDefinition(resourceType);
			Class<? extends IBaseResource> nextTypeClass = def.getImplementingClass();
			ISearchBuilder sb = mySearchBuilderFactory.newSearchBuilder(dao, resourceType, nextTypeClass);
			myResourceTypeToSearchBuilder.put(resourceType, sb);
		}
		return myResourceTypeToSearchBuilder.get(resourceType);
	}

	protected RuntimeSearchParam getPatientSearchParamForCurrentResourceType(String theResourceType) {
		RuntimeSearchParam searchParam = null; // TODO - should this cache?
		Optional<RuntimeSearchParam> onlyPatientSearchParamForResourceType = SearchParameterUtil.getOnlyPatientSearchParamForResourceType(myContext, theResourceType);
		if (onlyPatientSearchParamForResourceType.isPresent()) {
			searchParam = onlyPatientSearchParamForResourceType.get();
		}
		return searchParam;
	}

	private List<SearchParameterMap> createSearchParameterMapsForResourceType(RuntimeResourceDefinition theDef, ExportPIDIteratorParameters theParams) {
		String resourceType = theDef.getName();
		String[] typeFilters = theParams.getFilters().toArray(new String[0]); // lame...
		List<SearchParameterMap> spMaps = null;
		spMaps = Arrays.stream(typeFilters)
			.filter(typeFilter -> typeFilter.startsWith(resourceType + "?"))
			.map(filter -> buildSearchParameterMapForTypeFilter(filter, theDef, theParams.getStartDate()))
			.collect(Collectors.toList());

		//None of the _typeFilters applied to the current resource type, so just make a simple one.
		if (spMaps.isEmpty()) {
			SearchParameterMap defaultMap = new SearchParameterMap();
			enhanceSearchParameterMapWithCommonParameters(defaultMap, theParams.getStartDate());
			spMaps = Collections.singletonList(defaultMap);
		}

		return spMaps;
	}

	@Override
	public BulkExportJobInfo getJobInfo(String theJobId) {
		Optional<BulkExportJobEntity> jobOp = myBulkExportJobDao.findByJobId(theJobId);

		if (jobOp.isPresent()) {
			BulkExportJobEntity jobEntity = jobOp.get();
			BulkExportJobInfo jobInfo = new BulkExportJobInfo();

			jobInfo.setJobId(jobEntity.getJobId());
			Set<String> resourceTypes = new HashSet<>();
			for (BulkExportCollectionEntity collection : jobEntity.getCollections()) {
				resourceTypes.add(collection.getResourceType());
			}
			jobInfo.setResourceTypes(resourceTypes);
			return jobInfo;
		}
		else {
			return null;
		}
	}

	private SearchParameterMap buildSearchParameterMapForTypeFilter(String theFilter, RuntimeResourceDefinition theDef, Date theSinceDate) {
		SearchParameterMap searchParameterMap = myMatchUrlService.translateMatchUrl(theFilter, theDef);
		enhanceSearchParameterMapWithCommonParameters(searchParameterMap, theSinceDate);
		return searchParameterMap;
	}

	private void enhanceSearchParameterMapWithCommonParameters(SearchParameterMap map, Date theSinceDate) {
		map.setLoadSynchronous(true);
		if (theSinceDate != null) {
			map.setLastUpdated(new DateRangeParam(theSinceDate, null));
		}
	}

	@Override
	public void setJobStatus(String theJobId, BulkExportJobStatusEnum theStatus) {
		myBulkExportDaoSvc.setJobToStatus(theJobId, theStatus, null);
	}

	@Override
	public BulkExportJobStatusEnum getJobStatus(String theJobId) {
		Optional<BulkExportJobEntity> jobOp = myBulkExportJobDao.findByJobId(theJobId);

		if (jobOp.isPresent()) {
			return jobOp.get().getStatus();
		}
		else {
			String msg = "Invalid id. No such job : " + theJobId;
			ourLog.error(msg);
			throw new ResourceNotFoundException(theJobId);
		}
	}

	@Override
	public void addFileToCollection(String theJobId, String theResourceType, IIdType theBinaryId) {
		Map<Long, String> collectionMap = myBulkExportDaoSvc.getBulkJobCollectionIdToResourceTypeMap(theJobId);

		Long collectionId = null;
		for (Map.Entry<Long, String> entrySet : collectionMap.entrySet()) {
			if (entrySet.getValue().equals(theResourceType)) {
				collectionId = entrySet.getKey();
				break;
			}
		}

		if (collectionId == null) {
			String msg = "No matching collection for resource type "
				+ theResourceType
				+ " for job "
				+ theJobId;
			ourLog.error(msg);

			throw new InvalidRequestException(msg);
		}

		BulkExportCollectionFileEntity file = new BulkExportCollectionFileEntity();
		file.setResource(theBinaryId.getIdPart());

		myBulkExportDaoSvc.addFileToCollectionWithId(collectionId, file);
	}

	/** For Patient **/

	private RuntimeSearchParam validateSearchParametersForPatient(SearchParameterMap expandedSpMap, ExportPIDIteratorParameters theParams) {
		RuntimeSearchParam runtimeSearchParam = getPatientSearchParamForCurrentResourceType(theParams.getResourceType());
		if (expandedSpMap.get(runtimeSearchParam.getName()) != null) {
			throw new IllegalArgumentException(Msg.code(796) + String.format("Patient Bulk Export manually modifies the Search Parameter called [%s], so you may not include this search parameter in your _typeFilter!", runtimeSearchParam.getName()));
		}
		return runtimeSearchParam;
	}

	/** for group exports **/

	private RuntimeSearchParam validateSearchParametersForGroup(SearchParameterMap expandedSpMap, String theResourceType) {
		RuntimeSearchParam runtimeSearchParam = getPatientSearchParamForCurrentResourceType(theResourceType);
		if (expandedSpMap.get(runtimeSearchParam.getName()) != null) {
			throw new IllegalArgumentException(Msg.code(792) + String.format("Group Bulk Export manually modifies the Search Parameter called [%s], so you may not include this search parameter in your _typeFilter!", runtimeSearchParam.getName()));
		}
		return runtimeSearchParam;
	}

	/**
	 * In case we are doing a Group Bulk Export and resourceType `Patient` is requested, we can just return the group members,
	 * possibly expanded by MDM, and don't have to go and fetch other resource DAOs.
	 */
	private Iterator<ResourcePersistentId> getExpandedPatientIterator(ExportPIDIteratorParameters theParameters) {
		List<String> members = getMembers(theParameters.getGroupId());
		List<IIdType> ids = members.stream().map(member -> new IdDt("Patient/" + member)).collect(Collectors.toList());
		List<Long> pidsOrThrowException = myJpaIdHelperService.getPidsOrThrowException(ids);
		Set<Long> patientPidsToExport = new HashSet<>(pidsOrThrowException);

		if (theParameters.isExpandMdm()) {
			SystemRequestDetails srd = SystemRequestDetails.newSystemRequestAllPartitions();
			IBaseResource group = myDaoRegistry.getResourceDao("Group").read(new IdDt(theParameters.getGroupId()), srd);
			Long pidOrNull = myJpaIdHelperService.getPidOrNull(group);
			List<IMdmLinkDao.MdmPidTuple> goldenPidSourcePidTuple = myMdmLinkDao.expandPidsFromGroupPidGivenMatchResult(pidOrNull, MdmMatchResultEnum.MATCH);
			goldenPidSourcePidTuple.forEach(tuple -> {
				patientPidsToExport.add(tuple.getGoldenPid());
				patientPidsToExport.add(tuple.getSourcePid());
			});
			populateMdmResourceCache(goldenPidSourcePidTuple);
		}
		List<ResourcePersistentId> resourcePersistentIds = patientPidsToExport
			.stream()
			.map(ResourcePersistentId::new)
			.collect(Collectors.toList());
		return resourcePersistentIds.iterator();
	}

	/**
	 * Given the local myGroupId, read this group, and find all members' patient references.
	 * @return A list of strings representing the Patient IDs of the members (e.g. ["P1", "P2", "P3"]
	 */
	private List<String> getMembers(String theGroupId) {
		SystemRequestDetails requestDetails = SystemRequestDetails.newSystemRequestAllPartitions();
		IBaseResource group = myDaoRegistry.getResourceDao("Group").read(new IdDt(theGroupId), requestDetails);
		List<IPrimitiveType> evaluate = myContext.newFhirPath().evaluate(group, "member.entity.reference", IPrimitiveType.class);
		return  evaluate.stream().map(IPrimitiveType::getValueAsString).collect(Collectors.toList());
	}


	/**
	 * @param thePidTuples
	 */
	private void populateMdmResourceCache(List<IMdmLinkDao.MdmPidTuple> thePidTuples) {
		if (myMdmExpansionCacheSvc.hasBeenPopulated()) {
			return;
		}
		//First, convert this zipped set of tuples to a map of
		//{
		//   patient/gold-1 -> [patient/1, patient/2]
		//   patient/gold-2 -> [patient/3, patient/4]
		//}
		Map<Long, Set<Long>> goldenResourceToSourcePidMap = new HashMap<>();
		extract(thePidTuples, goldenResourceToSourcePidMap);

		//Next, lets convert it to an inverted index for fast lookup
		// {
		//   patient/1 -> patient/gold-1
		//   patient/2 -> patient/gold-1
		//   patient/3 -> patient/gold-2
		//   patient/4 -> patient/gold-2
		// }
		Map<String, String> sourceResourceIdToGoldenResourceIdMap = new HashMap<>();
		goldenResourceToSourcePidMap.forEach((key, value) -> {
			String goldenResourceId = myIdHelperService.translatePidIdToForcedIdWithCache(new ResourcePersistentId(key)).orElse(key.toString());
			Map<Long, Optional<String>> pidsToForcedIds = myIdHelperService.translatePidsToForcedIds(value);

			Set<String> sourceResourceIds = pidsToForcedIds.entrySet().stream()
				.map(ent -> ent.getValue().isPresent() ? ent.getValue().get() : ent.getKey().toString())
				.collect(Collectors.toSet());

			sourceResourceIds
				.forEach(sourceResourceId -> sourceResourceIdToGoldenResourceIdMap.put(sourceResourceId, goldenResourceId));
		});

		//Now that we have built our cached expansion, store it.
		myMdmExpansionCacheSvc.setCacheContents(sourceResourceIdToGoldenResourceIdMap);
	}

	private void extract(List<IMdmLinkDao.MdmPidTuple> theGoldenPidTargetPidTuples, Map<Long, Set<Long>> theGoldenResourceToSourcePidMap) {
		for (IMdmLinkDao.MdmPidTuple goldenPidTargetPidTuple : theGoldenPidTargetPidTuples) {
			Long goldenPid = goldenPidTargetPidTuple.getGoldenPid();
			Long sourcePid = goldenPidTargetPidTuple.getSourcePid();
			theGoldenResourceToSourcePidMap.computeIfAbsent(goldenPid, key -> new HashSet<>()).add(sourcePid);
		}
	}

	private void queryResourceTypeWithReferencesToPatients(Set<ResourcePersistentId> myReadPids,
																			 List<String> idChunk,
																			 ExportPIDIteratorParameters theParams,
																			 RuntimeResourceDefinition theDef) {
		//Build SP map
		//First, inject the _typeFilters and _since from the export job
		List<SearchParameterMap> expandedSpMaps = createSearchParameterMapsForResourceType(theDef, theParams);
		for (SearchParameterMap expandedSpMap: expandedSpMaps) {

			//Since we are in a bulk job, we have to ensure the user didn't jam in a patient search param, since we need to manually set that.
			validateSearchParametersForGroup(expandedSpMap, theParams.getResourceType());

			// Now, further filter the query with patient references defined by the chunk of IDs we have.
			filterSearchByResourceIds(idChunk, expandedSpMap, theParams.getResourceType());

			// Fetch and cache a search builder for this resource type
			ISearchBuilder searchBuilder = getSearchBuilderForLocalResourceType(theParams);

			//Execute query and all found pids to our local iterator.
			IResultIterator resultIterator = searchBuilder.createQuery(expandedSpMap, new SearchRuntimeDetails(null, theParams.getJobId()), null, RequestPartitionId.allPartitions());
			while (resultIterator.hasNext()) {
				myReadPids.add(resultIterator.next());
			}
		}
	}


	private void filterSearchByResourceIds(List<String> idChunk, SearchParameterMap expandedSpMap, String theResourceType) {
		ReferenceOrListParam orList =  new ReferenceOrListParam();
		idChunk.forEach(id -> orList.add(new ReferenceParam(id)));
		expandedSpMap.add(getPatientSearchParamForCurrentResourceType(theResourceType).getName(), orList);
	}

	/**
	 * Given the local myGroupId, perform an expansion to retrieve all resource IDs of member patients.
	 * if myMdmEnabled is set to true, we also reach out to the IMdmLinkDao to attempt to also expand it into matched
	 * patients.
	 *
	 * @return a Set of Strings representing the resource IDs of all members of a group.
	 */
	private Set<String> expandAllPatientPidsFromGroup(ExportPIDIteratorParameters theParams) {
		Set<String> expandedIds = new HashSet<>();
		SystemRequestDetails requestDetails = SystemRequestDetails.newSystemRequestAllPartitions();
		IBaseResource group = myDaoRegistry.getResourceDao("Group").read(new IdDt(theParams.getGroupId()), requestDetails);
		Long pidOrNull = myJpaIdHelperService.getPidOrNull(group);

		//Attempt to perform MDM Expansion of membership
		if (theParams.isExpandMdm()) {
			List<IMdmLinkDao.MdmPidTuple> goldenPidTargetPidTuples = myMdmLinkDao.expandPidsFromGroupPidGivenMatchResult(pidOrNull, MdmMatchResultEnum.MATCH);
			//Now lets translate these pids into resource IDs
			Set<Long> uniquePids = new HashSet<>();
			goldenPidTargetPidTuples.forEach(tuple -> {
				uniquePids.add(tuple.getGoldenPid());
				uniquePids.add(tuple.getSourcePid());
			});
			Map<Long, Optional<String>> pidToForcedIdMap = myIdHelperService.translatePidsToForcedIds(uniquePids);

			Map<Long, Set<Long>> goldenResourceToSourcePidMap = new HashMap<>();
			extract(goldenPidTargetPidTuples, goldenResourceToSourcePidMap);
			populateMdmResourceCache(goldenPidTargetPidTuples);

			//If the result of the translation is an empty optional, it means there is no forced id, and we can use the PID as the resource ID.
			Set<String> resolvedResourceIds = pidToForcedIdMap.entrySet().stream()
				.map(entry -> entry.getValue().isPresent() ? entry.getValue().get() : entry.getKey().toString())
				.collect(Collectors.toSet());

			expandedIds.addAll(resolvedResourceIds);
		}

		//Now manually add the members of the group (its possible even with mdm expansion that some members dont have MDM matches,
		//so would be otherwise skipped
		expandedIds.addAll(getMembers(theParams.getGroupId()));

		return expandedIds;
	}
}
