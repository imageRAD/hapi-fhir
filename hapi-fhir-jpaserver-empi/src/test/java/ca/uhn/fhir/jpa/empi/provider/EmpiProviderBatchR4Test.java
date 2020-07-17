package ca.uhn.fhir.jpa.empi.provider;

import ca.uhn.fhir.interceptor.api.IInterceptorService;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.test.concurrency.PointcutLatch;
import org.hl7.fhir.r4.model.Person;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.fail;

public class EmpiProviderBatchR4Test extends BaseLinkR4Test {

	protected Practitioner myPractitioner;
	protected StringType myPractitionerId;
	protected Person myPractitionerPerson;
	protected StringType myPractitionerPersonId;

	@Autowired
	IInterceptorService myInterceptorService;
	PointcutLatch afterEmpiLatch = new PointcutLatch(Pointcut.EMPI_AFTER_PERSISTED_RESOURCE_CHECKED);


	@BeforeEach
	public void before() {
		super.before();
		myPractitioner = createPractitionerAndUpdateLinks(new Practitioner());
		myPractitionerId = new StringType(myPractitioner.getIdElement().getValue());
		myPractitionerPerson = getPersonFromTarget(myPractitioner);
		myPractitionerPersonId = new StringType(myPractitionerPerson.getIdElement().getValue());

		myInterceptorService.registerAnonymousInterceptor(Pointcut.EMPI_AFTER_PERSISTED_RESOURCE_CHECKED, afterEmpiLatch);
		afterEmpiLatch.clear();
	}

	@Test
	public void testBatchRunOnAllPractitioners() {
		assertLinkCount(2);
		StringType practitionerType = new StringType("Practitioner");
		StringType criteria = null;
		myEmpiProviderR4.clearEmpiLinks(null);

		afterEmpiLatch.runWithExpectedCount(1, () -> {
			myEmpiProviderR4.batchRunEmpi(practitionerType, criteria, null);
		});
		assertLinkCount(1);
	}

	@Test
	public void testBatchRunOnAllPatients() throws InterruptedException {
		assertLinkCount(2);
		StringType patientType = new StringType("Patient");
		StringType criteria = null;
		afterEmpiLatch.runWithExpectedCount(1, () -> {
			myEmpiProviderR4.batchRunEmpi(patientType, criteria, null);
		});
		assertLinkCount(1);
	}

	@Test
	public void testBatchRunOnInvalidType() throws InterruptedException {
		StringType observationType= new StringType("Observation");
		StringType criteria = null;
		myEmpiProviderR4.clearEmpiLinks(null);
		try {
			myEmpiProviderR4.batchRunEmpi(observationType, criteria, null);
			fail();
		} catch(InvalidRequestException e) {
			assertThat(e.getMessage(), is(equalTo("$empi-batch-run does not support resource type: Observation")));
		}
	}

	@Test
	public void testBatchRunOnAllTypes() throws InterruptedException {
		assertLinkCount(2);
		StringType patientType = new StringType("Patient");
		StringType criteria = null;
		myEmpiProviderR4.clearEmpiLinks(null);

		afterEmpiLatch.runWithExpectedCount(1, () -> {
			myEmpiProviderR4.batchRunEmpi(patientType, criteria, null);
		});

		assertLinkCount(1);
	}

	@Test
	public void testBatchRunOnAllTypesWithInvalidCriteria() {
		assertLinkCount(2);
		StringType criteria = new StringType("Patient?death-date=2020-06-01");
		StringType targetType = new StringType("Practitioner");
		myEmpiProviderR4.clearEmpiLinks(null);

		try {
			myEmpiProviderR4.batchRunEmpi(targetType, criteria, null);
			fail();
		} catch(InvalidRequestException e) {
			assertThat(e.getMessage(), is(equalTo("Failed to parse match URL[Patient?death-date=2020-06-01] - Resource type Practitioner does not have a parameter with name: death-date")));
		}
	}
}