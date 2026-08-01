package com.marketplace.api.payment;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * PayFast's ITN webhook. UNAUTHENTICATED by design (PayFast has no JWT);
 * authenticity comes from the four checks in PayfastItnService plus the
 * source-host gate here. Requires a permitAll carve-out in SecurityConfig,
 * same as the Stripe webhook.
 *
 * The raw body is consumed as a STRING and parsed by hand: field order on
 * the wire is part of the signature, and servlet parameter maps do not
 * preserve it.
 *
 * Response policy: 200 for anything that parsed and was processed OR
 * dropped by a business check (retrying a forged payload cannot fix it);
 * 403 only for source-host failures, where "go away" is the message.
 */
@RestController
public class PayfastController {

    private static final Logger log = LoggerFactory.getLogger(PayfastController.class);

    /** PayFast's published notification sources (docs, 2026-08-01). */
    private static final List<String> VALID_HOSTS = List.of(
            "www.payfast.co.za", "w1w.payfast.co.za", "w2w.payfast.co.za",
            "sandbox.payfast.co.za");

    private final PayfastItnService itnService;
    private final boolean verifySource;

    public PayfastController(PayfastItnService itnService,
                             @Value("${app.payfast.verify-source:true}") boolean verifySource) {
        this.itnService = itnService;
        this.verifySource = verifySource;
    }

    @PostMapping(value = "/api/v1/payments/payfast/itn",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> itn(@RequestBody String rawBody, HttpServletRequest request) {
        if (verifySource && !fromPayfast(request)) {
            log.warn("PayFast ITN dropped: source {} not a PayFast host",
                    clientIp(request));
            return ResponseEntity.status(403).build();
        }
        itnService.handle(rawBody);
        return ResponseEntity.ok().build();
    }

    /**
     * Defense-in-depth, the weakest of the checks (we sit behind Railway's
     * proxy, so the client IP comes from X-Forwarded-For's first hop). DNS
     * is resolved per request on a miss; PayFast's ladder retries anything
     * dropped during a transient resolution failure.
     */
    private boolean fromPayfast(HttpServletRequest request) {
        String ip = clientIp(request);
        if (ip == null) return false;
        Set<String> validIps = new HashSet<>();
        for (String host : VALID_HOSTS) {
            try {
                Arrays.stream(InetAddress.getAllByName(host))
                        .map(InetAddress::getHostAddress)
                        .forEach(validIps::add);
            } catch (Exception e) {
                // One unresolvable host must not veto the rest.
            }
        }
        return validIps.contains(ip);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].strip();
        }
        return request.getRemoteAddr();
    }
}
