package org.openmrs.module.kenyaemr;

import org.junit.Test;
import org.openmrs.module.kenyaemr.util.FhirIngestParser;
import org.openmrs.module.kenyaemr.util.FhirIngestParser.ParsedPatient;
import org.openmrs.module.kenyaemr.util.FhirIngestParser.ParsedObservation;

import java.util.List;

import static org.junit.Assert.*;

public class FhirIngestParserTest {

    @Test
    public void parsePatientAndObservations_happyPath() throws Exception {
        String bundle = "{\n" +
                "  \"resourceType\": \"Bundle\",\n" +
                "  \"entry\": [\n" +
                "    {\n" +
                "      \"resource\": {\n" +
                "        \"resourceType\": \"Patient\",\n" +
                "        \"id\": \"p-123\",\n" +
                "        \"name\": [{ \"given\": [\"Naomi\"], \"family\": \"Kamau\" }],\n" +
                "        \"gender\": \"female\",\n" +
                "        \"birthDate\": \"1988-05-01\"\n" +
                "      }\n" +
                "    },\n" +
                "    {\n" +
                "      \"resource\": {\n" +
                "        \"resourceType\": \"Observation\",\n" +
                "        \"code\": { \"text\": \"Weight\" },\n" +
                "        \"valueQuantity\": { \"value\": 62.5, \"unit\": \"kg\" }\n" +
                "      }\n" +
                "    },\n" +
                "    {\n" +
                "      \"resource\": {\n" +
                "        \"resourceType\": \"Observation\",\n" +
                "        \"code\": { \"text\": \"Systolic blood pressure\" },\n" +
                "        \"valueQuantity\": { \"value\": 120, \"unit\": \"mmHg\" }\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        ParsedPatient p = FhirIngestParser.parsePatient(bundle);
        assertNotNull(p);
        assertEquals("p-123", p.id);
        assertEquals("Naomi", p.given);
        assertEquals("Kamau", p.family);

        List<ParsedObservation> obs = FhirIngestParser.parseObservations(bundle);
        assertNotNull(obs);
        assertEquals(2, obs.size());
        ParsedObservation weight = obs.get(0);
        assertEquals("Weight", weight.codeText);
        assertEquals(Double.valueOf(62.5), weight.valueNumeric);

        ParsedObservation sys = obs.get(1);
        assertTrue(sys.codeText.toLowerCase().contains("systolic"));
        assertEquals(Double.valueOf(120.0), sys.valueNumeric);
    }
}
