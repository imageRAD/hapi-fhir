package ca.uhn.fhir.jpa.api.pid;

import ca.uhn.fhir.rest.api.server.storage.ResourcePersistentId;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ResourcePidListBuilder {
	private static final IResourcePidList EMPTY_CHUNK = new EmptyResourcePidList();

	// FIXME KHS test
	public static IResourcePidList fromChunksAndDate(List<IResourcePidList> theChunks, Date theEnd) {
		if (theChunks.isEmpty()) {
			return empty();
		}

		Set<ResourcePersistentId> ids = new LinkedHashSet<>();

		Date endDate = null;
		boolean containsMixed = false;
		for (IResourcePidList chunk : theChunks) {
			ids.addAll(chunk.getIds());
			endDate = getLatestDate(chunk, endDate, theEnd);
			if (chunk instanceof MixedResourcePidList) {
				containsMixed = true;
			}
		}

		if (containsMixed) {
			List<String> types = new ArrayList<>();
			for (IResourcePidList chunk : theChunks) {
				for (int i = 0; i < chunk.size(); ++i) {
					types.add(chunk.getResourceType(i));
				}
			}
			return new MixedResourcePidList(ids, types, endDate);
		} else {
			IResourcePidList firstChunk = theChunks.get(0);
			String onlyResourceType = firstChunk.getResourceType(0);
			return new HomogeneousResourcePidList(onlyResourceType, ids, endDate);
		}
	}

	private static Date getLatestDate(IResourcePidList theChunk, Date theCurrentEndDate, Date thePassedInEndDate) {
		Date endDate = theCurrentEndDate;
		if (theCurrentEndDate == null) {
			endDate = theChunk.getLastDate();
		} else if (theChunk.getLastDate().after(endDate)
			&& theChunk.getLastDate().before(thePassedInEndDate)) {
			endDate = theChunk.getLastDate();
		}
		return endDate;
	}

	private static IResourcePidList empty() {
		return EMPTY_CHUNK;
	}
}
