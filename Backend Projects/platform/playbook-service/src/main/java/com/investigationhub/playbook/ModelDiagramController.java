package com.investigationhub.playbook;

import io.swagger.v3.oas.annotations.Operation;
import java.io.IOException;
import org.flowable.cmmn.api.CmmnRepositoryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v1/playbooks/model")
public class ModelDiagramController {
    private final CmmnRepositoryService repository;

    public ModelDiagramController(CmmnRepositoryService repository) {
        this.repository = repository;
    }

    @GetMapping(value = "/diagram", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "View the latest deployed CMMN case model as a PNG")
    public ResponseEntity<byte[]> diagram() throws IOException {
        var definition = repository.createCaseDefinitionQuery()
                .caseDefinitionKey("investigation").latestVersion().singleResult();
        if (definition == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Investigation model not deployed");
        }
        try (var stream = repository.getCaseDiagram(definition.getId())) {
            if (stream == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "CMMN model has no diagram layout");
            }
            return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(stream.readAllBytes());
        }
    }
}
