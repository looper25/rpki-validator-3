package net.ripe.rpki.validator3.adapter.jpa;

import com.querydsl.core.BooleanBuilder;
import net.ripe.rpki.validator3.domain.RpkiRepositories;
import net.ripe.rpki.validator3.domain.RpkiRepository;
import net.ripe.rpki.validator3.domain.TrustAnchor;
import net.ripe.rpki.validator3.domain.ValidationRuns;
import net.ripe.rpki.validator3.domain.constraints.ValidLocationURI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import javax.transaction.Transactional;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static net.ripe.rpki.validator3.domain.querydsl.QRpkiRepository.rpkiRepository;

@Repository
@Transactional(Transactional.TxType.REQUIRED)
public class JPARpkiRepositories extends JPARepository<RpkiRepository> implements RpkiRepositories {
    private final QuartzValidationScheduler quartzValidationScheduler;
    private final ValidationRuns validationRuns;

    @Autowired
    public JPARpkiRepositories(QuartzValidationScheduler quartzValidationScheduler, ValidationRuns validationRuns) {
        super(rpkiRepository);
        this.quartzValidationScheduler = quartzValidationScheduler;
        this.validationRuns = validationRuns;
    }

    @Override
    public RpkiRepository register(@NotNull @Valid TrustAnchor trustAnchor, @NotNull @ValidLocationURI String uri, RpkiRepository.Type type) {
        RpkiRepository result = findByURI(uri).orElseGet(() -> {
            RpkiRepository repository = new RpkiRepository(trustAnchor, uri, type);
            entityManager.persist(repository);
            if (repository.getType() == RpkiRepository.Type.RRDP) {
                quartzValidationScheduler.addRpkiRepository(repository);
            }
            return repository;
        });
        result.addTrustAnchor(trustAnchor);
        if (type == RpkiRepository.Type.RSYNC && result.getType() == RpkiRepository.Type.RSYNC_PREFETCH) {
            result.setType(RpkiRepository.Type.RSYNC);
        }
        return result;
    }

    @Override
    public Optional<RpkiRepository> findByURI(@NotNull @ValidLocationURI String uri) {
        String normalized = uri;
        return Optional.ofNullable(select().where(
            rpkiRepository.rrdpNotifyUri.eq(normalized).or(rpkiRepository.rsyncRepositoryUri.eq(normalized))
        ).fetchFirst());
    }

    @Override
    public List<RpkiRepository> findAll(RpkiRepository.Status optionalStatus) {
        BooleanBuilder builder = new BooleanBuilder();
        if (optionalStatus != null) {
            builder.and(rpkiRepository.status.eq(optionalStatus));
        }
        return select().where(builder).fetch();
    }

    @Override
    public Stream<RpkiRepository> findRsyncRepositories() {
        return stream(
            select()
                .where(rpkiRepository.type.in(RpkiRepository.Type.RSYNC, RpkiRepository.Type.RSYNC_PREFETCH))
                .orderBy(rpkiRepository.rsyncRepositoryUri.asc(), rpkiRepository.id.asc())
        );
    }

    @Override
    public void removeAllForTrustAnchor(TrustAnchor trustAnchor) {
        for (RpkiRepository repository : select().where(rpkiRepository.trustAnchors.contains(trustAnchor)).fetch()) {
            repository.removeTrustAnchor(trustAnchor);
            if (repository.getTrustAnchors().isEmpty()) {
                if (repository.getType() == RpkiRepository.Type.RRDP) {
                    quartzValidationScheduler.removeRpkiRepository(repository);
                }
                validationRuns.removeAllForRpkiRepository(repository);
                entityManager.remove(repository);
            }
        }
    }
}
