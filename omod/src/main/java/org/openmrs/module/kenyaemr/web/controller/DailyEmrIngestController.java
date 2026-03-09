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
            InputStream in = request.getInputStream();
            byte[] bytes = in.readAllBytes();
            String body = new String(bytes, StandardCharsets.UTF_8);

            // Ensure target dir exists
            String dir = "/tmp/dailyemr-bundles";
            Files.createDirectories(Paths.get(dir));

            // Use timestamp for filename; try to include patient id if present
            String filename = String.format("%s/bundle-%d.json", dir, System.currentTimeMillis());
            try (FileWriter fw = new FileWriter(new File(filename))) {
                fw.write(body);
            }

            log.info("Received FHIR bundle and wrote to " + filename);

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
