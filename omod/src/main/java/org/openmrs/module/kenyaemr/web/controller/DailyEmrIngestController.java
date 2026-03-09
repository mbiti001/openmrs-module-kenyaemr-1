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

            // Try to parse minimal JSON metadata (patient id, entry count)
            String patientRef = null;
            int entryCount = 0;
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(body);
                if (root.has("entry") && root.get("entry").isArray()) {
                    entryCount = root.get("entry").size();
                    for (com.fasterxml.jackson.databind.JsonNode e : root.get("entry")) {
                        if (e.has("resource") && e.get("resource").has("resourceType") && "Patient".equals(e.get("resource").get("resourceType").asText())) {
                            com.fasterxml.jackson.databind.JsonNode p = e.get("resource");
                            if (p.has("id")) {
                                patientRef = p.get("id").asText();
                            } else if (p.has("identifier") && p.get("identifier").isArray() && p.get("identifier").size() > 0) {
                                com.fasterxml.jackson.databind.JsonNode idn = p.get("identifier").get(0);
                                if (idn.has("value")) patientRef = idn.get("value").asText();
                            }
                            break;
                        }
                    }
                }
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
