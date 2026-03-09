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

    public static class ParsedServiceRequest {
        public String id;
        public String codeText;
        public String authoredOn;
    }

    public static class ParsedDiagnosticReport {
        public String id;
        public String status;
        public String conclusion;
    }

    public static class ParsedMedication {
        public String id;
        public String medicationText;
        public String status;
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

    public static List<ParsedServiceRequest> parseServiceRequests(String bundleJson) throws Exception {
        List<ParsedServiceRequest> out = new ArrayList<ParsedServiceRequest>();
        JsonNode root = MAPPER.readTree(bundleJson);
        if (!root.has("entry") || !root.get("entry").isArray()) return out;

        for (JsonNode e : root.get("entry")) {
            JsonNode res = e.get("resource");
            if (res != null && res.has("resourceType") && "ServiceRequest".equals(res.get("resourceType").asText())) {
                ParsedServiceRequest r = new ParsedServiceRequest();
                if (res.has("id")) r.id = res.get("id").asText();
                if (res.has("code")) {
                    JsonNode code = res.get("code");
                    if (code.has("text")) r.codeText = code.get("text").asText();
                    else if (code.has("coding") && code.get("coding").isArray() && code.get("coding").size() > 0) {
                        JsonNode c0 = code.get("coding").get(0);
                        if (c0.has("display")) r.codeText = c0.get("display").asText();
                    }
                }
                if (res.has("authoredOn")) r.authoredOn = res.get("authoredOn").asText();
                out.add(r);
            }
        }
        return out;
    }

    public static List<ParsedDiagnosticReport> parseDiagnosticReports(String bundleJson) throws Exception {
        List<ParsedDiagnosticReport> out = new ArrayList<ParsedDiagnosticReport>();
        JsonNode root = MAPPER.readTree(bundleJson);
        if (!root.has("entry") || !root.get("entry").isArray()) return out;

        for (JsonNode e : root.get("entry")) {
            JsonNode res = e.get("resource");
            if (res != null && res.has("resourceType") && "DiagnosticReport".equals(res.get("resourceType").asText())) {
                ParsedDiagnosticReport r = new ParsedDiagnosticReport();
                if (res.has("id")) r.id = res.get("id").asText();
                if (res.has("status")) r.status = res.get("status").asText();
                if (res.has("conclusion")) r.conclusion = res.get("conclusion").asText();
                out.add(r);
            }
        }
        return out;
    }

    public static List<ParsedMedication> parseMedications(String bundleJson) throws Exception {
        List<ParsedMedication> out = new ArrayList<ParsedMedication>();
        JsonNode root = MAPPER.readTree(bundleJson);
        if (!root.has("entry") || !root.get("entry").isArray()) return out;

        for (JsonNode e : root.get("entry")) {
            JsonNode res = e.get("resource");
            if (res != null && res.has("resourceType")) {
                String rt = res.get("resourceType").asText();
                if ("MedicationRequest".equals(rt) || "MedicationDispense".equals(rt) || "MedicationStatement".equals(rt)) {
                    ParsedMedication m = new ParsedMedication();
                    if (res.has("id")) m.id = res.get("id").asText();
                    if (res.has("status")) m.status = res.get("status").asText();
                    if (res.has("medicationCodeableConcept")) {
                        JsonNode mc = res.get("medicationCodeableConcept");
                        if (mc.has("text")) m.medicationText = mc.get("text").asText();
                        else if (mc.has("coding") && mc.get("coding").isArray() && mc.get("coding").size() > 0) {
                            JsonNode c0 = mc.get("coding").get(0);
                            if (c0.has("display")) m.medicationText = c0.get("display").asText();
                        }
                    } else if (res.has("medicationReference")) {
                        JsonNode mr = res.get("medicationReference");
                        if (mr.has("display")) m.medicationText = mr.get("display").asText();
                        else if (mr.has("reference")) m.medicationText = mr.get("reference").asText();
                    }
                    out.add(m);
                }
            }
        }

        return out;
    }
}
