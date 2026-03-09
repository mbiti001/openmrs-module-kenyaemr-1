package org.openmrs.module.kenyaemr.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Small parser for extracting minimal patient and observation data from a FHIR Bundle JSON.
 * This is intentionally light-weight and used by the ingest controller; it avoids OpenMRS
 * runtime to keep parsing logic testable in isolation.
 */
public class FhirIngestParser {

    public static class ParsedPatient {
        public String id;
        public String given;
        public String family;
        public String gender;
        public String birthDate;
    }

    public static class ParsedObservation {
        public String codeText;
        public String valueString;
        public Double valueNumeric;
        public String unit;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ParsedPatient parsePatient(String bundleJson) throws Exception {
        JsonNode root = MAPPER.readTree(bundleJson);
        if (!root.has("entry") || !root.get("entry").isArray()) return null;

        for (JsonNode e : root.get("entry")) {
            JsonNode res = e.get("resource");
            if (res != null && res.has("resourceType") && "Patient".equals(res.get("resourceType").asText())) {
                ParsedPatient p = new ParsedPatient();
                if (res.has("id")) p.id = res.get("id").asText();
                // identifiers
                if ((p.id == null || p.id.isEmpty()) && res.has("identifier") && res.get("identifier").isArray() && res.get("identifier").size() > 0) {
                    JsonNode idn = res.get("identifier").get(0);
                    if (idn.has("value")) p.id = idn.get("value").asText();
                }

                if (res.has("name") && res.get("name").isArray() && res.get("name").size() > 0) {
                    JsonNode name = res.get("name").get(0);
                    if (name.has("given") && name.get("given").isArray() && name.get("given").size() > 0) {
                        p.given = name.get("given").get(0).asText();
                    }
                    if (name.has("family")) p.family = name.get("family").asText();
                }

                if (res.has("gender")) p.gender = res.get("gender").asText();
                if (res.has("birthDate")) p.birthDate = res.get("birthDate").asText();
                return p;
            }
        }
        return null;
    }

    public static List<ParsedObservation> parseObservations(String bundleJson) throws Exception {
        List<ParsedObservation> out = new ArrayList<ParsedObservation>();
        JsonNode root = MAPPER.readTree(bundleJson);
        if (!root.has("entry") || !root.get("entry").isArray()) return out;

        for (JsonNode e : root.get("entry")) {
            JsonNode res = e.get("resource");
            if (res != null && res.has("resourceType") && "Observation".equals(res.get("resourceType").asText())) {
                ParsedObservation o = new ParsedObservation();
                // code text
                if (res.has("code")) {
                    JsonNode code = res.get("code");
                    if (code.has("text")) o.codeText = code.get("text").asText();
                    else if (code.has("coding") && code.get("coding").isArray() && code.get("coding").size() > 0) {
                        JsonNode c0 = code.get("coding").get(0);
                        if (c0.has("display")) o.codeText = c0.get("display").asText();
                    }
                }

                // value
                if (res.has("valueQuantity")) {
                    JsonNode vq = res.get("valueQuantity");
                    if (vq.has("value")) o.valueNumeric = vq.get("value").asDouble();
                    if (vq.has("unit")) o.unit = vq.get("unit").asText();
                } else if (res.has("valueString")) {
                    o.valueString = res.get("valueString").asText();
                } else if (res.has("valueQuantity")) {
                    // handled above
                } else if (res.has("component") && res.get("component").isArray()) {
                    // some BP obs have components: look for systolic/diastolic
                    Iterator<JsonNode> it = res.get("component").elements();
                    while (it.hasNext()) {
                        JsonNode comp = it.next();
                        String text = null;
                        if (comp.has("code")) {
                            JsonNode code = comp.get("code");
                            if (code.has("text")) text = code.get("text").asText();
                            else if (code.has("coding") && code.get("coding").isArray() && code.get("coding").size() > 0) {
                                JsonNode c0 = code.get("coding").get(0);
                                if (c0.has("display")) text = c0.get("display").asText();
                            }
                        }
                        if (comp.has("valueQuantity") && text != null) {
                            ParsedObservation compObs = new ParsedObservation();
                            compObs.codeText = text;
                            JsonNode vq = comp.get("valueQuantity");
                            if (vq.has("value")) compObs.valueNumeric = vq.get("value").asDouble();
                            if (vq.has("unit")) compObs.unit = vq.get("unit").asText();
                            out.add(compObs);
                        }
                    }
                    continue; // components already added
                }

                out.add(o);
            }
        }

        return out;
    }
}
