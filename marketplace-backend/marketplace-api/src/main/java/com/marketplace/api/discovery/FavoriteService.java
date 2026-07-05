package com.marketplace.api.discovery;

import com.marketplace.api.exception.ProductExceptions.ProductNotFoundException;
import com.marketplace.api.repository.ProductRepository;
import com.marketplace.api.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Favorites: idempotent PUT/DELETE — a heart tapped twice is one heart,
 * and an unfavorite on an already-unfavorited product is success. Both
 * verbs succeed regardless of prior state, so double-taps and optimistic
 * UI retries are structurally harmless.
 *
 * The unique(user, product) constraint in V9 backstops the one real race
 * (two simultaneous first-taps). FavoriteService.add catches the resulting
 * DataIntegrityViolationException and treats it as success, per the house
 * pattern from reviews.
 */
@Service
class FavoriteService {

    private final FavoriteRepository favoriteRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    FavoriteService(FavoriteRepository favoriteRepository,
                    ProductRepository productRepository,
                    UserRepository userRepository) {
        this.favoriteRepository = favoriteRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public void add(Long userId, Long productId) {
        if (!productRepository.existsByIdAndDeletedAtIsNull(productId)) {
            throw new ProductNotFoundException(productId);
        }
        if (favoriteRepository.existsByUserIdAndProductId(userId, productId)) {
            return;   // idempotent: already hearted is success
        }
        Favorite f = new Favorite();
        f.setUser(userRepository.getReferenceById(userId));
        f.setProduct(productRepository.getReferenceById(productId));
        try {
            favoriteRepository.saveAndFlush(f);
        } catch (DataIntegrityViolationException e) {
            // two first-taps raced; the other one won — still success
        }
    }

    @Transactional
    public void remove(Long userId, Long productId) {
        favoriteRepository.findByUserIdAndProductId(userId, productId)
                .ifPresent(favoriteRepository::delete);   // idempotent: absent is success
    }

    @Transactional(readOnly = true)
    public Page<Favorite> list(Long userId, Pageable pageable) {
        return favoriteRepository.findByUserIdAndProductDeletedAtIsNull(userId, pageable);
    }
}
