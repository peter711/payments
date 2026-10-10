package com.softfal.payments.gateway.stripe;

import com.softfal.payments.contracts.event.PaymentRequested;
import com.softfal.payments.gateway.GatewayTimeoutException;
import com.softfal.payments.gateway.InvalidGatewayRequestException;
import com.softfal.payments.gateway.PaymentGateway;
import com.softfal.payments.gateway.PaymentGatewayResult;
import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.AuthenticationException;
import com.stripe.exception.CardException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.service.PaymentIntentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * {@link PaymentGateway} adapter for the Stripe API.
 *
 * <p>A payment is charged by creating and confirming a {@link PaymentIntent} in
 * one synchronous call, so the outcome is known before the processor records
 * it. {@code paymentId} is sent as the Stripe {@code Idempotency-Key}, which is
 * what makes retries safe on the provider side: repeating a request within
 * Stripe's idempotency window returns the original PaymentIntent instead of
 * charging again.</p>
 *
 * <p>Mapping of Stripe outcomes and errors:</p>
 * <ul>
 *   <li>{@code succeeded} → success;</li>
 *   <li>any other status ({@code requires_payment_method}, {@code requires_action},
 *       {@code processing}, ...) → failure, so the payment is recorded as failed
 *       rather than retried forever; asynchronous confirmation (3DS, delayed
 *       methods) is out of scope for this synchronous flow;</li>
 *   <li>{@link CardException} → failure with the decline reason;</li>
 *   <li>{@link AuthenticationException} → {@link InvalidGatewayRequestException}
 *       (a configuration error, never succeeds on retry);</li>
 *   <li>{@link ApiConnectionException}, {@link RateLimitException} and other
 *       {@link StripeException}s → {@link GatewayTimeoutException} (transient,
 *       retried by the consumer).</li>
 * </ul>
 */
public class StripePaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentGateway.class);

    private static final String STATUS_SUCCEEDED = "succeeded";

    private final PaymentIntentService paymentIntents;
    private final StripeGatewayProperties properties;

    public StripePaymentGateway(StripeClient stripeClient, StripeGatewayProperties properties) {
        this.paymentIntents = stripeClient.paymentIntents();
        this.properties = properties;
    }

    @Override
    public PaymentGatewayResult callPaymentGateway(PaymentRequested event) {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(CurrencyMinorUnits.toMinorUnits(event.amount(), event.currency()))
                .setCurrency(event.currency().toLowerCase(Locale.ROOT))
                .setPaymentMethod(properties.paymentMethod())
                .setConfirm(true)
                .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.AUTOMATIC)
                // Confirming server-side cannot complete a redirect-based method,
                // so enable automatic payment methods but forbid redirects. Without
                // this Stripe rejects the confirmation asking for a return_url.
                .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                        .setEnabled(true)
                        .setAllowRedirects(
                                PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER)
                        .build())
                .setDescription("payment " + event.paymentId())
                .putMetadata("paymentId", event.paymentId())
                .putMetadata("accountId", event.accountId())
                .build();

        RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey(event.paymentId())
                .setBaseUrl(properties.apiBase())
                .setConnectTimeout(properties.timeoutMs())
                .setReadTimeout(properties.timeoutMs())
                .setMaxNetworkRetries(properties.maxNetworkRetries())
                .build();

        try {
            PaymentIntent intent = paymentIntents.create(params, options);
            return interpret(intent, event);
        } catch (CardException ex) {
            String reason = declineReason(ex);
            log.warn("Stripe declined paymentId={} code={} declineCode={}",
                    event.paymentId(), ex.getCode(), ex.getDeclineCode());
            return PaymentGatewayResult.failure(reason);
        } catch (InvalidRequestException ex) {
            log.warn("Stripe rejected paymentId={} as invalid: {}", event.paymentId(), ex.getMessage());
            return PaymentGatewayResult.failure("Stripe rejected the payment request: " + ex.getMessage());
        } catch (AuthenticationException ex) {
            throw new InvalidGatewayRequestException(
                    "Stripe authentication failed; check payment.stripe.api-key: " + ex.getMessage(), ex);
        } catch (ApiConnectionException | RateLimitException ex) {
            throw new GatewayTimeoutException(
                    "Stripe call failed transiently for paymentId=" + event.paymentId() + ": " + ex.getMessage());
        } catch (StripeException ex) {
            throw new GatewayTimeoutException(
                    "Stripe call failed for paymentId=" + event.paymentId() + ": " + ex.getMessage());
        }
    }

    private PaymentGatewayResult interpret(PaymentIntent intent, PaymentRequested event) {
        String status = intent.getStatus();
        log.info("Stripe PaymentIntent id={} for paymentId={} returned status={}",
                intent.getId(), event.paymentId(), status);
        if (STATUS_SUCCEEDED.equals(status)) {
            return PaymentGatewayResult.success();
        }
        return PaymentGatewayResult.failure("Stripe payment not completed (status=" + status + ")");
    }

    private static String declineReason(CardException ex) {
        if (ex.getDeclineCode() != null && !ex.getDeclineCode().isBlank()) {
            return "Stripe declined the payment (" + ex.getDeclineCode() + ")";
        }
        if (ex.getCode() != null && !ex.getCode().isBlank()) {
            return "Stripe declined the payment (" + ex.getCode() + ")";
        }
        return "Stripe declined the payment";
    }
}
