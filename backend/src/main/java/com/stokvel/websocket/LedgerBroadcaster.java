package com.stokvel.websocket;

import com.stokvel.service.LedgerService;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Pushes the ledger to every subscribed client on /topic/ledger.
 *
 * Called directly by each service at the end of a mutating method, rather than wired
 * through Spring's application-event mechanism. Events would be the tidier
 * decoupling; the direct call is one line, obvious when walking through the code in
 * a demo, and deliberately chosen over ceremony for a build this size.
 *
 * It pushes the *whole* ledger, not the lines that just changed. The server owns
 * state and clients re-render from what is pushed, so there is no client-side merge
 * to write and no way for a client to drift out of agreement with the database. A
 * demo's worth of rows makes the payload size a non-question.
 *
 * It holds LedgerService and nothing else — it never reads a repository or decides
 * what a row means. The one thing it knows is where to send what it is given.
 */
@Component
public class LedgerBroadcaster {

    public static final String TOPIC = "/topic/ledger";

    private final SimpMessagingTemplate messagingTemplate;
    private final LedgerService ledgerService;

    public LedgerBroadcaster(SimpMessagingTemplate messagingTemplate, LedgerService ledgerService) {
        this.messagingTemplate = messagingTemplate;
        this.ledgerService = ledgerService;
    }

    /** Re-reads the ledger and pushes it. Called after the write, never before. */
    public void broadcast() {
        messagingTemplate.convertAndSend(TOPIC, ledgerService.getLedger());
    }
}
