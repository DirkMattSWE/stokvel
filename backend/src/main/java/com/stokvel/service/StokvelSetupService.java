package com.stokvel.service;

import com.stokvel.model.Cycle;
import com.stokvel.model.Member;
import com.stokvel.model.StokvelConfig;
import com.stokvel.repository.CycleRepository;
import com.stokvel.repository.MemberRepository;
import com.stokvel.repository.StokvelConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;

/**
 * Creates the stokvel and its members. Every method here is an append — there is
 * no update or delete path, and there never will be.
 */
@Service
public class StokvelSetupService {

    /** One member per month of the year. The rotation is the bound, not the stokvel. */
    private static final long MAX_MEMBERS = 12L;

    private final StokvelConfigRepository configRepository;
    private final MemberRepository memberRepository;
    private final CycleRepository cycleRepository;

    public StokvelSetupService(StokvelConfigRepository configRepository,
                               MemberRepository memberRepository,
                               CycleRepository cycleRepository) {
        this.configRepository = configRepository;
        this.memberRepository = memberRepository;
        this.cycleRepository = cycleRepository;
    }

    /** Both rows written by {@link #addMember(String)}. */
    public record MemberAdded(Member member, Cycle cycle) {
    }

    /**
     * Writes the singleton config row.
     *
     * startDate is the clock's opening value, supplied by the caller rather than
     * read from the system clock, so the simulation and production run the same
     * code path (Rule 8).
     *
     * The existsById guard is load-bearing, not defensive noise: save() on a row
     * that already exists is an UPDATE, so without it a second call would silently
     * rewrite the stokvel's terms — the contribution amount and rotation count that
     * members already committed to. The guard is what keeps this an append.
     */
    @Transactional
    public StokvelConfig createStokvel(BigDecimal contributionAmount,
                                       LocalDate startDate,
                                       Integer rotationCount) {
        if (configRepository.existsById(StokvelConfigRepository.SINGLETON_ID)) {
            throw new IllegalStateException(
                    "This stokvel already exists. There is one, and it is never recreated.");
        }
        if (contributionAmount == null || contributionAmount.signum() <= 0) {
            throw new IllegalArgumentException("Contribution amount must be greater than zero.");
        }
        if (startDate == null) {
            throw new IllegalArgumentException("A start date is required: it is the clock's first value.");
        }
        if (rotationCount == null || rotationCount < 1) {
            throw new IllegalArgumentException("A stokvel runs at least one rotation.");
        }

        return configRepository.save(new StokvelConfig(
                StokvelConfigRepository.SINGLETON_ID, contributionAmount, startDate, rotationCount));
    }

    /**
     * Appends a member, and the one cycle in which that member is the recipient.
     *
     * Writing both rows here is what makes "the rotation is exactly as long as the
     * member count" (Rule 9) an invariant the database cannot drift from, rather
     * than something a separate setup step establishes and a later joiner has to
     * re-establish. A member joining late is therefore not a special case: their
     * cycle lands at the tail of the current rotation, which is Rule 3 for free.
     *
     * Exactly one cycle, never rotation_count of them. Appending a member's cycles
     * for every rotation in one go orders the rotation A, A, B, B, C, C instead of
     * A, B, C, A, B, C, and correcting that means inserting rows between existing
     * ones. Later rotations are generated whole, when the current one closes.
     *
     * The member's created_at comes from the simulated clock (Rule 8), because here
     * it is business data twice over: it is the rotation order, and compared against
     * a cycle's due date it is what decides whether a late joiner was liable for the
     * round already in progress.
     */
    @Transactional
    public MemberAdded addMember(String name) {
        StokvelConfig config = configRepository.require();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A member needs a name.");
        }
        if (memberRepository.count() >= MAX_MEMBERS) {
            throw new IllegalStateException(
                    "This stokvel is full at " + MAX_MEMBERS + " members — one per month of the year.");
        }

        Member member = memberRepository.save(new Member(name.trim(), config.simulatedNow()));
        Cycle cycle = cycleRepository.save(appendCycleFor(member, config));
        return new MemberAdded(member, cycle);
    }

    /**
     * The new cycle goes at the tail: next sequence number, same rotation as the
     * current tail, due one month after it. Nothing existing is renumbered or
     * moved, which is what lets sequence_number be trusted at all.
     *
     * The due date chains off the tail cycle rather than off config.current_date,
     * because current_date moves as the clock advances (Rule 8) — a member joining
     * in month three would otherwise be handed a due date computed from month three.
     */
    private Cycle appendCycleFor(Member recipient, StokvelConfig config) {
        Optional<Cycle> tail = cycleRepository.findTopByOrderBySequenceNumberDesc();

        int sequenceNumber = tail.map(cycle -> cycle.getSequenceNumber() + 1).orElse(1);
        int rotationNumber = tail.map(Cycle::getRotationNumber).orElse(1);
        LocalDate dueDate = tail
                .map(cycle -> endOfMonth(cycle.getDueDate().plusMonths(1)))
                .orElseGet(() -> endOfMonth(config.getCurrentDate()));

        return new Cycle(sequenceNumber, rotationNumber, dueDate, recipient);
    }

    /** Cycle boundaries are end of month (Rule 8). */
    private static LocalDate endOfMonth(LocalDate date) {
        return date.with(TemporalAdjusters.lastDayOfMonth());
    }
}
