package ca.uhn.fhir.batch2.jobs.mdm;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.mdm.api.IMdmSettings;
import ca.uhn.fhir.mdm.rules.json.MdmRulesJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MdmJobParametersValidatorTest {

	MdmJobParametersValidator myMdmJobParametersValidator;
	@Mock
	private DaoRegistry myDaoRegistry;
	@Mock
	private IMdmSettings myMdmSettings;

	@BeforeEach
	public void before() {
		myMdmJobParametersValidator = new MdmJobParametersValidator(myDaoRegistry, myMdmSettings);
	}

	@Test
	public void testDisabled() {
		// setup
		MdmJobParameters parameters = new MdmJobParameters();

		// execute
		List<String> result = myMdmJobParametersValidator.validate(parameters);

		// verify
		assertThat(result, hasSize(1));
		assertEquals("Mdm is not enabled on this server", result.get(0));
	}

	@Test
	public void testNoResourceType() {
		// setup
		MdmJobParameters parameters = new MdmJobParameters();
		when(myMdmSettings.isEnabled()).thenReturn(true);

		// execute
		List<String> result = myMdmJobParametersValidator.validate(parameters);

		// verify
		assertThat(result, hasSize(1));
		assertEquals("Mdm Clear Job Parameters must define at least one resource type", result.get(0));
	}

	@Test
	public void testInvalidResourceType() {
		// setup
		MdmJobParameters parameters = new MdmJobParameters().addResourceType("Immunization");
		when(myMdmSettings.isEnabled()).thenReturn(true);
		MdmRulesJson rules = new MdmRulesJson();
		rules.setMdmTypes(List.of("Patient"));
		when(myMdmSettings.getMdmRules()).thenReturn(rules);

		// execute
		List<String> result = myMdmJobParametersValidator.validate(parameters);

		// verify
		assertThat(result, hasSize(2));
		assertEquals("Resource type 'Immunization' is not supported on this server.", result.get(0));
		assertEquals("There are no mdm rules for resource type 'Immunization'", result.get(1));
	}

	@Test
	public void testSuccess() {
		// setup
		MdmJobParameters parameters = new MdmJobParameters().addResourceType("Patient");
		when(myMdmSettings.isEnabled()).thenReturn(true);
		MdmRulesJson rules = new MdmRulesJson();
		rules.setMdmTypes(List.of("Patient"));
		when(myMdmSettings.getMdmRules()).thenReturn(rules);
		when(myDaoRegistry.isResourceTypeSupported("Patient")).thenReturn(true);

		// execute
		List<String> result = myMdmJobParametersValidator.validate(parameters);

		// verify
		assertThat(result, hasSize(0));
	}

}
