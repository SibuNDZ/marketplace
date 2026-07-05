package com.marketplace.api.security;

import com.marketplace.api.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * The authenticated principal. Carries the database id so every protected
 * service method can call service.doSomething(principal.getId()) — identity
 * always comes from the token, never from a user-supplied request parameter.
 *
 * Role mapping: Spring's hasRole("VENDOR") checks for authority "ROLE_VENDOR",
 * so the prefix is applied here, once, and nowhere else.
 */
public class UserPrincipal implements UserDetails {

    private final Long id;
    private final String email;
    private final String passwordHash;
    private final String role;
    private final boolean enabled;

    public UserPrincipal(Long id, String email, String passwordHash, String role, boolean enabled) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = enabled;
    }

    public static UserPrincipal from(User user) {
        return new UserPrincipal(
                user.getId(),
                user.getEmail(),
                user.getPassword(),
                user.getRole().name(),
                Boolean.TRUE.equals(user.getIsActive())
        );
    }

    public Long getId() { return id; }
    public String getRole() { return role; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return email; }
    @Override public boolean isEnabled() { return enabled; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
}
