package io.burpabet.wallet.saga;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import io.burpabet.common.annotations.OutboxOperation;
import io.burpabet.common.annotations.Retryable;
import io.burpabet.common.annotations.ServiceFacade;
import io.burpabet.common.annotations.TransactionBoundary;
import io.burpabet.common.domain.Registration;
import io.burpabet.common.domain.Status;
import io.burpabet.common.util.Money;
import io.burpabet.common.util.RandomData;
import io.burpabet.wallet.model.AccountType;
import io.burpabet.wallet.model.CustomerAccount;
import io.burpabet.wallet.model.OperatorAccount;
import io.burpabet.wallet.service.AccountService;
import io.burpabet.wallet.service.NoSuchAccountException;
import io.burpabet.wallet.service.TransferRequest;
import io.burpabet.wallet.service.TransferService;

@ServiceFacade
public class WalletRegistrationFacade {
    private static final Money WELCOME_BONUS = Money.of("50.00", "USD");

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransferService transferService;

    /**
     * Create monetary account for customer and grant welcome bonus by debiting
     * operator accounts.
     *
     * @param registration the customer in pending state
     */
    @TransactionBoundary
    @Retryable
    @OutboxOperation(aggregateType = "registration")
    public Registration createAccounts(Registration registration) {
        Optional<OperatorAccount> optional = Objects.nonNull(registration.getOperatorId())
                ? accountService.findOperatorAccountById(registration.getOperatorId())
                : Optional.empty();

        // Create mandatory operator account on demand - for easier demo purposes
        OperatorAccount operatorAccount = optional.orElseGet(() -> accountService.createOperatorAccount(
                OperatorAccount.builder()
                        .withJurisdiction(registration.getJurisdiction())
                        .withBalance(Money.of("0.00", "USD"))
                        .withName("operator-" + registration.getJurisdiction()
                                + "-implicit-for-" + registration.getEmail())
                        .withAccountType(AccountType.LIABILITY)
                        .withDescription(RandomData.randomRoachFact())
                        .withAllowNegative(true)
                        .build()));

        CustomerAccount customerAccount = accountService.createCustomerAccount(
                CustomerAccount.builder()
                        .withJurisdiction(registration.getJurisdiction())
                        .withForeignId(registration.getEntityId())
                        .withOperatorId(operatorAccount.getId())
                        .withBalance(Money.of("0.00", "USD"))
                        .withName(registration.getName())
                        .withAccountType(AccountType.EXPENSE)
                        .withDescription(RandomData.randomRoachFact())
                        .withAllowNegative(false)
                        .build());

        grantWelcomeBonus(registration.getEventId(), customerAccount, operatorAccount);

        registration.setOperatorId(operatorAccount.getId());
        registration.setStatus(Status.APPROVED);
        registration.setStatusDetail("Welcome bonus granted");
        registration.setOrigin("wallet-service");

        logger.info("Registration approved and welcome bonus granted: {}", registration);

        return registration;
    }

    /**
     * A compensating action must be idempotent and retryable. Let's assume the accounts are still around and if
     * not its a fail.
     *
     * @param registration the current registration state
     */
    @TransactionBoundary
    @Retryable
    public void reverseAccounts(Registration registration) {
        CustomerAccount customerAccount = accountService.findCustomerAccountByForeignId(registration.getEntityId())
                .orElseThrow(() -> new NoSuchAccountException(registration.getEntityId()));

        OperatorAccount operatorAccount = accountService.findOperatorAccountById(registration.getOperatorId())
                .orElseThrow(() -> new NoSuchAccountException(registration.getOperatorId()));

        reverseWelcomeBonus(registration.getEventId(), customerAccount, operatorAccount);

        logger.info("Registration rejected and welcome bonus reversed: {}", registration);
    }

    private void grantWelcomeBonus(UUID idempotencyKey,
                                   CustomerAccount customerAccount,
                                   OperatorAccount operatorAccount) {
        TransferRequest.Builder requestBuilder = TransferRequest.builder()
                .withId(idempotencyKey)
                .withJurisdiction(customerAccount.getJurisdiction())
                .withTransactionType("welcome-bonus")
                .withBookingDate(LocalDate.now());

        requestBuilder
                .addLeg()
                .withId(customerAccount.getId())
                .withAmount(WELCOME_BONUS)
                .withNote("Welcome bonus from " + operatorAccount.getName())
                .then()
                .addLeg()
                .withId(operatorAccount.getId())
                .withAmount(WELCOME_BONUS.negate())
                .withNote("Welcome bonus grant to " + customerAccount.getName())
                .then();

        transferService.submitTransferRequest(requestBuilder.build());
    }

    private void reverseWelcomeBonus(UUID idempotencyKey,
                                     CustomerAccount customerAccount,
                                     OperatorAccount operatorAccount) {
        TransferRequest.Builder requestBuilder = TransferRequest.builder()
                .withId(idempotencyKey)
                .withJurisdiction(customerAccount.getJurisdiction())
                .withTransactionType("welcome-bonus-reversal")
                .withBookingDate(LocalDate.now());

        requestBuilder
                .addLeg()
                .withId(customerAccount.getId())
                .withAmount(WELCOME_BONUS.negate())
                .withNote("Welcome bonus redacted by " + operatorAccount.getName())
                .then()
                .addLeg()
                .withId(operatorAccount.getId())
                .withAmount(WELCOME_BONUS)
                .withNote("Welcome bonus reversal for " + customerAccount.getName())
                .then();

        transferService.submitTransferRequest(requestBuilder.build());
    }
}
