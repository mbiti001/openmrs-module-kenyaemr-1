/**
 * Lightweight REST endpoint to accept FHIR bundles from DailyEMR clients
 */
package org.openmrs.module.kenyaemr.web.controller;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.module.webservices.rest.web.v1_0.controller.BaseRestController;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletRequest;
import org.openmrs.api.context.Context;
import org.openmrs.Patient;
import org.openmrs.PersonName;
import org.openmrs.PatientIdentifier;
import org.openmrs.PatientIdentifierType;
import org.openmrs.api.PatientService;
import org.openmrs.api.EncounterService;
import org.openmrs.api.ObsService;
import org.openmrs.Encounter;
import org.openmrs.Obs;
import org.openmrs.EncounterType;
import org.openmrs.Location;
import org.openmrs.module.metadatadeploy.MetadataUtils;
import org.openmrs.module.kenyaemr.Metadata;
import org.openmrs.module.kenyaemr.CommonMetadata;
import org.openmrs.module.kenyaemr.util.FhirIngestParser;
import org.openmrs.module.kenyaemr.util.FhirIngestParser.ParsedObservation;
import org.openmrs.module.kenyaemr.util.FhirIngestParser.ParsedPatient;
import org.openmrs.module.kenyaemr.KenyaEmrService;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

@Controller
@RequestMapping(value = "/rest/" + RestConstants.VERSION_1 + "/kenyaemr")
public class DailyEmrIngestController extends BaseRestController {

    protected final Log log = LogFactory.getLog(getClass());

    @RequestMapping(method = RequestMethod.POST, value = "/dailyemr/fhir-bundle")
    @ResponseBody
    public ResponseEntity<String> ingestFhirBundle(HttpServletRequest request) {
        try {
            // Simple token-based auth: header X-DailyEMR-Token must match env DAILYEMR_SECRET if set
            String provided = request.getHeader("X-DailyEMR-Token");
            String secret = System.getenv("DAILYEMR_SECRET");
            if (secret == null || secret.isEmpty()) {
                // fallback to system property if env not set
                secret = System.getProperty("kenyaemr.dailyemr.secret");
            }
            if (secret != null && !secret.isEmpty()) {
                if (provided == null || !provided.equals(secret)) {
                    log.warn("Rejected FHIR bundle request due to invalid token");
                    return new ResponseEntity<String>("Unauthorized", new HttpHeaders(), HttpStatus.UNAUTHORIZED);
                }
            }

            InputStream in = request.getInputStream();
            byte[] bytes = in.readAllBytes();
            String body = new String(bytes, StandardCharsets.UTF_8);

            // Parse minimal metadata using helper parser (keeps parsing logic testable)
            String patientRef = null;
            int entryCount = 0;
            ParsedPatient parsedPatient = null;
            List<ParsedObservation> parsedObservations = null;
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(body);
                if (root.has("entry") && root.get("entry").isArray()) {
                    entryCount = root.get("entry").size();
                }

                parsedPatient = FhirIngestParser.parsePatient(body);
                parsedObservations = FhirIngestParser.parseObservations(body);
                // parse additional resources
                List<FhirIngestParser.ParsedServiceRequest> serviceRequests = FhirIngestParser.parseServiceRequests(body);
                List<FhirIngestParser.ParsedDiagnosticReport> diagnosticReports = FhirIngestParser.parseDiagnosticReports(body);
                List<FhirIngestParser.ParsedMedication> medications = FhirIngestParser.parseMedications(body);
                if (parsedPatient != null && parsedPatient.id != null) patientRef = parsedPatient.id;
            } catch (Exception ex) {
                log.warn("Failed to parse FHIR bundle JSON metadata", ex);
            }

            // Ensure target dir exists
            String dir = "/tmp/dailyemr-bundles";
            Files.createDirectories(Paths.get(dir));

            // Use timestamp and patientRef for filename
            String filename = String.format("%s/bundle-%s-%d.json", dir, patientRef == null ? "unknown" : patientRef, System.currentTimeMillis());
            try (FileWriter fw = new FileWriter(new File(filename))) {
                fw.write(body);
            }

            log.info("Received FHIR bundle (entries=" + entryCount + ") patient=" + patientRef + " wrote to " + filename);

            // Attempt to persist minimal resources into OpenMRS: Patient -> Encounter -> Obs
            try {
                PatientService patientService = Context.getPatientService();
                EncounterService encounterService = Context.getEncounterService();
                ObsService obsService = Context.getObsService();
                KenyaEmrService kenyaEmr = Context.getService(KenyaEmrService.class);
                Location defaultLocation = kenyaEmr.getDefaultLocation();

                Patient patient = null;
                if (patientRef != null) {
                    List<Patient> found = patientService.getPatients(null, patientRef, null, false);
                    if (found != null && !found.isEmpty()) patient = found.get(0);
                }

                if (patient == null && parsedPatient != null) {
                    patient = new Patient();
                    if (parsedPatient.given != null || parsedPatient.family != null) {
                        PersonName name = new PersonName();
                        name.setGivenName(parsedPatient.given);
                        name.setFamilyName(parsedPatient.family);
                        patient.addName(name);
                    }
                    if (parsedPatient.gender != null) patient.setGender(parsedPatient.gender);
                    if (parsedPatient.birthDate != null) {
                        try {
                            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
                            patient.setBirthdate(sdf.parse(parsedPatient.birthDate));
                        } catch (Exception ignore) {}
                    }

                    // Add identifier using OPENMRS_ID identifier type if available
                    try {
                        PatientIdentifierType idType = MetadataUtils.existing(PatientIdentifierType.class, CommonMetadata._PatientIdentifierType.OPENMRS_ID);
                        if (patientRef != null && idType != null) {
                            PatientIdentifier pid = new PatientIdentifier(patientRef, idType, defaultLocation);
                            patient.addIdentifier(pid);
                        }
                    } catch (Exception ex) {
                        // ignore identifier creation failures
                    }

                    patient = patientService.savePatient(patient);
                }

                if (patient != null && parsedObservations != null && !parsedObservations.isEmpty()) {
                    // create a simple encounter
                    Encounter enc = new Encounter();
                    enc.setPatient(patient);
                    enc.setEncounterDatetime(new java.util.Date());
                    enc.setLocation(defaultLocation);
                    // pick a sensible encounter type if available
                    try {
                        EncounterType et = null;
                        List<EncounterType> ets = encounterService.getAllEncounterTypes();
                        if (ets != null && !ets.isEmpty()) et = ets.get(0);
                        if (et != null) enc.setEncounterType(et);
                    } catch (Exception ex) {
                        // ignore
                    }

                    enc = encounterService.saveEncounter(enc);

                    // Save observations as simple obs linked to the encounter
                    for (ParsedObservation po : parsedObservations) {
                        try {
                            Obs o = new Obs();
                            o.setPerson(patient);
                            o.setEncounter(enc);
                            o.setObsDatetime(new java.util.Date());
                            o.setLocation(defaultLocation);

                            // crude mapping by text
                            String t = po.codeText == null ? "" : po.codeText.toLowerCase();
                            if (t.contains("weight")) {
                                o.setConcept(org.openmrs.module.kenyaemr.Dictionary.getConcept(Metadata.Concept.WEIGHT_KG));
                                if (po.valueNumeric != null) o.setValueNumeric(po.valueNumeric);
                            } else if (t.contains("height")) {
                                o.setConcept(org.openmrs.module.kenyaemr.Dictionary.getConcept(Metadata.Concept.HEIGHT_CM));
                                if (po.valueNumeric != null) o.setValueNumeric(po.valueNumeric);
                            } else if (t.contains("systolic")) {
                                o.setConcept(org.openmrs.module.kenyaemr.Dictionary.getConcept("5085AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
                                if (po.valueNumeric != null) o.setValueNumeric(po.valueNumeric);
                            } else if (t.contains("diastolic")) {
                                o.setConcept(org.openmrs.module.kenyaemr.Dictionary.getConcept("5086AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
                                if (po.valueNumeric != null) o.setValueNumeric(po.valueNumeric);
                            } else if (t.contains("pulse") || t.contains("heart rate")) {
                                o.setConcept(org.openmrs.module.kenyaemr.Dictionary.getConcept("5087AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
                                if (po.valueNumeric != null) o.setValueNumeric(po.valueNumeric);
                            } else if (t.contains("temperature") || t.contains("temp")) {
                                o.setConcept(org.openmrs.module.kenyaemr.Dictionary.getConcept("5088AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
                                if (po.valueNumeric != null) o.setValueNumeric(po.valueNumeric);
                            } else if (t.contains("respiratory")) {
                                o.setConcept(org.openmrs.module.kenyaemr.Dictionary.getConcept("5242AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
                                if (po.valueNumeric != null) o.setValueNumeric(po.valueNumeric);
                            } else if (t.contains("oxygen") || t.contains("o2")) {
                                o.setConcept(org.openmrs.module.kenyaemr.Dictionary.getConcept("5092AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
                                if (po.valueNumeric != null) o.setValueNumeric(po.valueNumeric);
                            } else {
                                // fallback: store as text obs with a generic concept if available
                                try {
                                    o.setConcept(org.openmrs.module.kenyaemr.Dictionary.getConcept(Metadata.Concept.OTHER_SPECIFY));
                                    if (po.valueString != null) o.setValueText(po.valueString);
                                    else if (po.valueNumeric != null) o.setValueNumeric(po.valueNumeric);
                                } catch (Exception ex) {
                                    // last fallback: skip obs
                                    continue;
                                }
                            }

                            obsService.saveObs(o, "DailyEMR ingest");
                        } catch (Exception ex) {
                            log.warn("Failed to save parsed observation", ex);
                        }
                    }
                    }

                    // Persist service requests, diagnostic reports and medications as text obs attached to the same encounter
                    try {
                        if (patient != null) {
                            // ensure we have an encounter (create one if missing from above)
                            Encounter encToUse = null;
                            try {
                                List<Encounter> patientEncounters = Context.getEncounterService().getEncountersByPatient(patient);
                                if (patientEncounters != null && !patientEncounters.isEmpty()) encToUse = patientEncounters.get(0);
                            } catch (Exception ex) {}

                            if (encToUse == null) {
                                encToUse = new Encounter();
                                encToUse.setPatient(patient);
                                encToUse.setEncounterDatetime(new java.util.Date());
                                encToUse.setLocation(defaultLocation);
                                try {
                                    EncounterType et = null;
                                    List<EncounterType> ets = Context.getEncounterService().getAllEncounterTypes();
                                    if (ets != null && !ets.isEmpty()) et = ets.get(0);
                                    if (et != null) encToUse.setEncounterType(et);
                                } catch (Exception ex) {}
                                encToUse = Context.getEncounterService().saveEncounter(encToUse);
                            }

                            // ServiceRequests
                            if (serviceRequests != null) {
                                for (FhirIngestParser.ParsedServiceRequest sr : serviceRequests) {
                                    try {
                                        Obs o = new Obs();
                                        o.setPerson(patient);
                                        o.setEncounter(encToUse);
                                        o.setObsDatetime(new java.util.Date());
                                        o.setLocation(defaultLocation);
                                        o.setConcept(org.openmrs.module.kenyaemr.Dictionary.getConcept(Metadata.Concept.OTHER_SPECIFY));
                                        String text = "ServiceRequest: " + (sr.codeText == null ? "(unknown)" : sr.codeText) + " authoredOn=" + sr.authoredOn;
                                        o.setValueText(text);
                                        Context.getObsService().saveObs(o, "DailyEMR ingest");
                                    } catch (Exception ex) {
                                        log.warn("Failed to save service request as obs", ex);
                                    }
                                }
                            }

                            // DiagnosticReports
                            if (diagnosticReports != null) {
                                for (FhirIngestParser.ParsedDiagnosticReport dr : diagnosticReports) {
                                    try {
                                        Obs o = new Obs();
                                        o.setPerson(patient);
                                        o.setEncounter(encToUse);
                                        o.setObsDatetime(new java.util.Date());
                                        o.setLocation(defaultLocation);
                                        o.setConcept(org.openmrs.module.kenyaemr.Dictionary.getConcept(Metadata.Concept.OTHER_SPECIFY));
                                        String text = "DiagnosticReport: status=" + dr.status + " conclusion=" + dr.conclusion;
                                        o.setValueText(text);
                                        Context.getObsService().saveObs(o, "DailyEMR ingest");
                                    } catch (Exception ex) {
                                        log.warn("Failed to save diagnostic report as obs", ex);
                                    }
                                }
                            }

                            // Medications
                            if (medications != null) {
                                for (FhirIngestParser.ParsedMedication m : medications) {
                                    try {
                                        Obs o = new Obs();
                                        o.setPerson(patient);
                                        o.setEncounter(encToUse);
                                        o.setObsDatetime(new java.util.Date());
                                        o.setLocation(defaultLocation);
                                        o.setConcept(org.openmrs.module.kenyaemr.Dictionary.getConcept(Metadata.Concept.OTHER_SPECIFY));
                                        String text = "Medication: " + (m.medicationText == null ? "(unknown)" : m.medicationText) + " status=" + m.status;
                                        o.setValueText(text);
                                        Context.getObsService().saveObs(o, "DailyEMR ingest");
                                    } catch (Exception ex) {
                                        log.warn("Failed to save medication as obs", ex);
                                    }
                                }
                            }
                        }
                    } catch (Exception ex) {
                        log.warn("Failed to persist additional FHIR resources", ex);
                    }
                }
            } catch (Exception ex) {
                log.warn("OpenMRS persistence of FHIR bundle failed", ex);
            }

            return new ResponseEntity<String>("OK", new HttpHeaders(), HttpStatus.OK);
        } catch (Exception e) {
            log.error("Failed to ingest FHIR bundle", e);
            return new ResponseEntity<String>("Failed to ingest: " + e.getMessage(), new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public String getNamespace() {
        return "v1/kenyaemr";
    }
}
