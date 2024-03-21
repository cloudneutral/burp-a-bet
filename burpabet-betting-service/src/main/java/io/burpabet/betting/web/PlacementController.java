package io.burpabet.betting.web;

import io.burpabet.betting.service.BetPlacementService;
import io.burpabet.betting.service.DuplicatePlacementException;
import io.burpabet.betting.service.RaceService;
import io.burpabet.betting.shell.HypermediaClient;
import io.burpabet.common.domain.BetPlacement;
import io.burpabet.common.util.Money;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.PagedModel;
import org.springframework.hateoas.mediatype.Affordances;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.view.RedirectView;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static io.burpabet.betting.shell.HypermediaClient.PAGED_MODEL_TYPE;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.afford;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

@RestController
@RequestMapping(path = "/api/placement")
public class PlacementController {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Autowired
    private RaceService raceService;

    @Autowired
    private BetPlacementService betPlacementService;

    @Autowired
    private HypermediaClient hypermediaClient;

    @GetMapping(value = "/")
    public ResponseEntity<PlacementModel> getPlacementForm() {
        PlacementModel form = new PlacementModel();
        form.setIdempotencyKey(UUID.randomUUID());
        form.setStake(Money.of("5.00", Money.USD));
        form.setRaceId(raceService.getRandomRace().getId());

        // Query customer API and pick a random customer
        try {
            Map<String, Object> tuples = hypermediaClient.traverseCustomerApi(
                    traverson -> Objects.requireNonNull(traverson
                                    .follow("customer:one"))
                            .toObject(Map.class));
            UUID customerId = UUID.fromString(tuples.get("id").toString());
            form.setCustomerId(customerId);
        } catch (RestClientException e) {
            // ok
        }

        form.add(Affordances.of(linkTo(methodOn(getClass()).getPlacementForm())
                        .withSelfRel()
                        .andAffordance(afford(methodOn(getClass())
                                .placeBet(null))))
                .toLink());

        return ResponseEntity.ok(form);
    }

    @PostMapping(value = "/")
    public HttpEntity<PlacementModel> placeBet(@Valid @RequestBody PlacementModel form) {
        BetPlacement betPlacement = new BetPlacement();
        betPlacement.setEventId(form.getIdempotencyKey());
        betPlacement.setCustomerId(form.getCustomerId());
        betPlacement.setRaceId(form.getRaceId());
        betPlacement.setStake(form.getStake());

        try {
            betPlacement = betPlacementService.placeBet(betPlacement);
        } catch (DuplicatePlacementException e) {
            PlacementModel resource = new PlacementModel();
            resource.add(linkTo(methodOn(BetController.class)
                    .getBet(betPlacement.getEntityId()))
                    .withSelfRel());
            return ResponseEntity.status(HttpStatus.OK).body(resource);
        }

        Link selfLink = linkTo(methodOn(BetController.class)
                .getBet(betPlacement.getEntityId()))
                .withSelfRel();

        return ResponseEntity.created(selfLink.toUri()).build();
    }

    @GetMapping(value = "/random")
    public RedirectView placeRandomBets() {
        try {
            PagedModel<Map<String, Object>> collection = hypermediaClient
                    .traverseCustomerApi(traverson -> Objects.requireNonNull(traverson
                            .follow("customer:all")
                            .withTemplateParameters(Map.of("page", 0, "size", 256))
                            .toObject(PAGED_MODEL_TYPE)));

            collection.getContent().forEach(map -> {
                BetPlacement betPlacement = new BetPlacement();
                betPlacement.setEventId(UUID.randomUUID());
                betPlacement.setCustomerId(UUID.fromString(map.get("id").toString()));
                betPlacement.setStake(Money.of("5.00", Money.USD));
                betPlacement.setRaceId(raceService.getRandomRace().getId());

                betPlacementService.placeBet(betPlacement);
            });
        } catch (RestClientException e) {
            logger.warn("Customer API error: " + e.getMessage());
        }

        return new RedirectView("/bets-placed");
    }
}
