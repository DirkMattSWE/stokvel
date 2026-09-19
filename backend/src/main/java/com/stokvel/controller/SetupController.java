package com.stokvel.controller;

import com.stokvel.dto.AddMemberRequest;
import com.stokvel.dto.AddMemberResponse;
import com.stokvel.dto.CreateStokvelRequest;
import com.stokvel.dto.StokvelConfigResponse;
import com.stokvel.model.StokvelConfig;
import com.stokvel.service.StokvelSetupService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin by design: unpacks the request, calls the service, maps the entity to a DTO.
 * No rule is decided here.
 */
@RestController
@RequestMapping("/api/setup")
public class SetupController {

    private final StokvelSetupService setupService;

    public SetupController(StokvelSetupService setupService) {
        this.setupService = setupService;
    }

    @PostMapping("/stokvel")
    public ResponseEntity<StokvelConfigResponse> createStokvel(@RequestBody CreateStokvelRequest request) {
        StokvelConfig config = setupService.createStokvel(
                request.contributionAmount(),
                request.startDate(),
                request.rotationCount());

        return ResponseEntity.status(HttpStatus.CREATED).body(StokvelConfigResponse.from(config));
    }

    @PostMapping("/members")
    public ResponseEntity<AddMemberResponse> addMember(@RequestBody AddMemberRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AddMemberResponse.from(setupService.addMember(request.name())));
    }
}
