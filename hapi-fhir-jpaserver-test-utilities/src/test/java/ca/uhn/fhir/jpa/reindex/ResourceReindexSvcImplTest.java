package ca.uhn.fhir.jpa.reindex;

import ca.uhn.fhir.jpa.api.svc.BatchResourceId;
import ca.uhn.fhir.jpa.api.svc.IBatchIdChunk;
import ca.uhn.fhir.jpa.api.svc.IResourceReindexSvc;
import ca.uhn.fhir.jpa.test.BaseJpaR4Test;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unchecked")
@TestMethodOrder(value = MethodOrderer.MethodName.class)
public class ResourceReindexSvcImplTest extends BaseJpaR4Test {

	@Autowired
	private IResourceReindexSvc mySvc;

	@Test
	public void testFetchResourceIdsPage_NoUrl_WithData() {

		// Setup

		createPatient(withActiveFalse());
		sleepUntilTimeChanges();

		Date start = new Date();

		Long id0 = createPatient(withActiveFalse()).getIdPartAsLong();
		sleepUntilTimeChanges();
		Long id1 = createPatient(withActiveFalse()).getIdPartAsLong();
		sleepUntilTimeChanges();
		Date beforeLastInRange = new Date();
		sleepUntilTimeChanges();
		Long id2 = createObservation(withObservationCode("http://foo", "bar")).getIdPartAsLong();
		sleepUntilTimeChanges();

		Date end = new Date();

		sleepUntilTimeChanges();

		createPatient(withActiveFalse());

		// Execute

		myCaptureQueriesListener.clear();
		IBatchIdChunk page = mySvc.fetchResourceIdsPage(start, end, null, null);

		// Verify

		assertEquals(3, page.size());
		assertThat(page.getBatchResourceIds(), contains(new BatchResourceId("Patient", id0), new BatchResourceId("Patient", id1), new BatchResourceId("Observation", id2)));
		assertTrue(page.getLastDate().after(beforeLastInRange));
		assertTrue(page.getLastDate().before(end));

		assertEquals(1, myCaptureQueriesListener.logSelectQueries().size());
		assertEquals(0, myCaptureQueriesListener.countInsertQueries());
		assertEquals(0, myCaptureQueriesListener.countUpdateQueries());
		assertEquals(0, myCaptureQueriesListener.countDeleteQueries());
		assertEquals(1, myCaptureQueriesListener.getCommitCount());
		assertEquals(0, myCaptureQueriesListener.getRollbackCount());

	}


	@Test
	public void testFetchResourceIdsPage_NoUrl_NoData() {

		// Setup

		Date start = new Date();
		Date end = new Date();

		// Execute

		myCaptureQueriesListener.clear();
		IBatchIdChunk page = mySvc.fetchResourceIdsPage(start, end, null, null);

		// Verify

		assertTrue(page.isEmpty());
		assertEquals(0, page.size());
		assertNull(page.getLastDate());

		assertEquals(1, myCaptureQueriesListener.logSelectQueries().size());
		assertEquals(0, myCaptureQueriesListener.countInsertQueries());
		assertEquals(0, myCaptureQueriesListener.countUpdateQueries());
		assertEquals(0, myCaptureQueriesListener.countDeleteQueries());
		assertEquals(1, myCaptureQueriesListener.getCommitCount());
		assertEquals(0, myCaptureQueriesListener.getRollbackCount());

	}


	@Test
	public void testFetchResourceIdsPage_WithUrl_WithData() {

		// Setup

		createPatient(withActiveFalse());
		sleepUntilTimeChanges();

		// Start of resources within range
		Date start = new Date();
		sleepUntilTimeChanges();
		Long id0 = createPatient(withActiveFalse()).getIdPartAsLong();
		createObservation(withObservationCode("http://foo", "bar"));
		createObservation(withObservationCode("http://foo", "bar"));
		sleepUntilTimeChanges();
		Date beforeLastInRange = new Date();
		sleepUntilTimeChanges();
		Long id1 = createPatient(withActiveFalse()).getIdPartAsLong();
		sleepUntilTimeChanges();
		Date end = new Date();
		sleepUntilTimeChanges();
		// End of resources within range

		createObservation(withObservationCode("http://foo", "bar"));
		createPatient(withActiveFalse());
		sleepUntilTimeChanges();

		// Execute

		myCaptureQueriesListener.clear();
		IBatchIdChunk page = mySvc.fetchResourceIdsPage(start, end, null, "Patient?active=false");

		// Verify

		assertEquals(2, page.size());
		List<BatchResourceId> batchResourceIds = page.getBatchResourceIds();
		assertThat(page.getBatchResourceIds(), contains(new BatchResourceId("Patient", id0), new BatchResourceId("Patient", id1)));
		assertTrue(page.getLastDate().after(beforeLastInRange));
		assertTrue(page.getLastDate().before(end));

		assertEquals(3, myCaptureQueriesListener.logSelectQueries().size());
		assertEquals(0, myCaptureQueriesListener.countInsertQueries());
		assertEquals(0, myCaptureQueriesListener.countUpdateQueries());
		assertEquals(0, myCaptureQueriesListener.countDeleteQueries());
		assertEquals(1, myCaptureQueriesListener.getCommitCount());
		assertEquals(0, myCaptureQueriesListener.getRollbackCount());

	}


}
