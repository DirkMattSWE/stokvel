package com.stokvel.controller;

import com.stokvel.service.LedgerService;
import com.stokvel.service.LedgerService.LedgerEntry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The ledger's initial load, and nothing else.
 *
 * A client that has just opened the page has received no push yet — nothing has
 * happened since it subscribed — so without this it would stare at a blank screen
 * until somebody made a payment. Every update after this one arrives over
 * /topic/ledger.
 *
 * Returns LedgerEntry directly. It carries no JPA entities, only strings, amounts
 * and an instant, so it is already the wire shape; a LedgerResponse would be a
 * field-for-field copy under a different name. The rule that DTOs cross the wire is
 * there to stop *entities* being serialised, and there are none here.
 *
 * There is no POST on this controller, and there never will be. The ledger is
 * derived from rows other services write — a way to add a line directly would be a
 * second source of truth, which is exactly what not having a ledger table prevents.
 */
@RestController
@RequestMapping("/api/ledger")
public class LedgerController {

    private final LedgerService ledgerService;

    public LedgerController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @GetMapping
    public List<LedgerEntry> ledger() {
        return ledgerService.getLedger();
    }
}
