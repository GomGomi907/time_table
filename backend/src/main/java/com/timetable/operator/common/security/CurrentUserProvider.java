package com.timetable.operator.common.security;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.auth.infrastructure.AppUserRepository;
import com.timetable.operator.common.config.AppProperties;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class CurrentUserProvider {

    private final AppUserRepository appUserRepository;
    private final AppProperties appProperties;

    @Transactional
    public AppUser getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            Object principal = authentication.getPrincipal();
            if (principal instanceof OidcUser oidcUser) {
                return upsertAuthenticatedUser(
                        oidcUser.getSubject(),
                        oidcUser.getEmail(),
                        oidcUser.getFullName()
                );
            }
            if (principal instanceof OAuth2User oauth2User && !"anonymousUser".equals(principal)) {
                String subject = oauth2User.getAttribute("sub");
                String email = oauth2User.getAttribute("email");
                String displayName = Optional.ofNullable(oauth2User.getAttribute("name"))
                        .map(Object::toString)
                        .orElse(email);
                return upsertAuthenticatedUser(subject, email, displayName);
            }
        }

        return getOrCreateLocalUser();
    }

    private AppUser upsertAuthenticatedUser(String subject, String email, String displayName) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Authenticated Google account must expose an email.");
        }

        AppUser user = appUserRepository.findByGoogleSubject(subject)
                .or(() -> appUserRepository.findByEmail(email))
                .orElseGet(AppUser::new);

        user.setGoogleSubject(subject);
        user.setEmail(email);
        user.setDisplayName(displayName == null || displayName.isBlank() ? email : displayName);
        user.setProvider("google");
        user.setDemoUser(false);
        return appUserRepository.save(user);
    }

    private AppUser getOrCreateLocalUser() {
        String email = appProperties.auth().defaultUserEmail();
        return appUserRepository.findByEmail(email)
                .orElseGet(() -> createLocalUser(email));
    }

    private AppUser createLocalUser(String email) {
        AppUser user = new AppUser();
        user.setEmail(email);
        user.setDisplayName(appProperties.auth().defaultUserName());
        user.setProvider("local");
        user.setDemoUser(true);

        try {
            return appUserRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            return appUserRepository.findByEmail(email)
                    .orElseThrow(() -> exception);
        }
    }
}
