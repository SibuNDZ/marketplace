package com.marketplace.api.discovery;

import com.marketplace.api.repository.ProductRepository;
import com.marketplace.api.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fire-and-forget view capture. The contract: a view write must NEVER
 * break, slow, or roll back a product page load. Three @Async traps,
 * each handled explicitly:
 *
 * 1. Self-invocation bypasses the proxy — @Async only works through a
 *    Spring proxy, so this is its OWN bean, called from ProductService.
 *    Inlining this method into the caller "for simplicity" silently makes
 *    it synchronous. (Also requires @EnableAsync on the application class.)
 * 2. The async thread has no transaction — REQUIRES_NEW is explicit
 *    (REQUIRED would also open one on a bare thread, but REQUIRES_NEW
 *    states the intent: this write shares fate with nothing).
 * 3. Exceptions on async threads vanish — so nothing escapes this method.
 *    A failed view write is a WARN log, full stop. Losing a view event is
 *    a rounding error; losing a page load to one is a bug.
 *
 * getReferenceById on both FKs: no extra SELECTs, just references. The
 * product was proven to exist by the caller (write happens only on the 200
 * path); the user by the JWT filter.
 */
@Component
public class ProductViewRecorder {

    private static final Logger log = LoggerFactory.getLogger(ProductViewRecorder.class);

    private final ProductViewRepository viewRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    public ProductViewRecorder(ProductViewRepository viewRepository,
                               ProductRepository productRepository,
                               UserRepository userRepository) {
        this.viewRepository = viewRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
    }

    /** userId null = anonymous view (counts for trending only). */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long productId, Long userId) {
        try {
            ProductView view = new ProductView();
            view.setProduct(productRepository.getReferenceById(productId));
            if (userId != null) {
                view.setUser(userRepository.getReferenceById(userId));
            }
            viewRepository.save(view);
        } catch (Exception e) {
            log.warn("View write failed for product {} (user {}): {}",
                    productId, userId, e.getMessage());
        }
    }
}
