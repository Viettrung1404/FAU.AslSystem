package com.asl.backend.identity;

import com.asl.backend.identity.application.IdentityService;
import com.asl.backend.identity.application.port.out.AccountRepository;
import com.asl.backend.identity.application.port.out.IdTokenVerifier;
import com.asl.backend.identity.application.api.IdentityApi.Account;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IdentityServiceTest {
    final AccountRepository repository = mock(AccountRepository.class);
    final IdTokenVerifier verifier = mock(IdTokenVerifier.class);
    final IdentityService service = new IdentityService(verifier, repository);
    @Test void absentOptionalHeaderIsGuestButSuppliedInvalidHeaderIsNot() {
        assertThat(service.optionalAccount(null)).isNull();
        for (String header : List.of("", "Basic abc", "Bearer ", "Bearer a b"))
            assertThatThrownBy(() -> service.optionalAccount(header)).isInstanceOf(ApplicationFailure.class)
                .extracting(e -> ((ApplicationFailure)e).kind()).isEqualTo(ApplicationFailure.Kind.UNAUTHENTICATED);
        verifyNoInteractions(repository, verifier);
    }
    @Test void resolvesVerifiedUidRatherThanAnEmail() {
        Account account = new Account(UUID.randomUUID(), "hidden@privaterelay.appleid.com", "Learner");
        when(verifier.verify("valid")).thenReturn(new IdTokenVerifier.Identity("verified-uid", account.email(), "Changed provider name", "apple.com"));
        when(repository.findByUid("verified-uid")).thenReturn(new AccountRepository.Stored(account,false));
        assertThat(service.requireAccount("Bearer valid")).isEqualTo(account);
        verify(repository).findByUid("verified-uid");
    }
    @Test void requiresBootstrapAndRejectsSuspensionAndDisallowedProvider() {
        when(verifier.verify("valid")).thenReturn(new IdTokenVerifier.Identity("uid", null, null, "google.com"));
        assertThatThrownBy(() -> service.requireAccount("Bearer valid")).isInstanceOf(ApplicationFailure.class)
            .extracting(e -> ((ApplicationFailure)e).kind()).isEqualTo(ApplicationFailure.Kind.UNAUTHENTICATED);
        when(repository.findByUid("uid")).thenReturn(new AccountRepository.Stored(new Account(UUID.randomUUID(),null,null),true));
        assertThatThrownBy(() -> service.requireAccount("Bearer valid")).isInstanceOf(ApplicationFailure.class)
            .extracting(e -> ((ApplicationFailure)e).kind()).isEqualTo(ApplicationFailure.Kind.FORBIDDEN);
        when(verifier.verify("password")).thenReturn(new IdTokenVerifier.Identity("uid",null,null,"password"));
        assertThatThrownBy(() -> service.requireAccount("Bearer password")).isInstanceOf(ApplicationFailure.class)
            .extracting(e -> ((ApplicationFailure)e).kind()).isEqualTo(ApplicationFailure.Kind.FORBIDDEN);
    }
    @Test void validatesTimezoneDuplicateConsentAndNameBoundsBeforePersistence() {
        when(verifier.verify("valid")).thenReturn(new IdTokenVerifier.Identity("uid",null,null,"google.com"));
        UUID consent=UUID.randomUUID();
        assertThatThrownBy(() -> service.bootstrap("Bearer valid", "GMT+07:00", List.of())).isInstanceOf(ApplicationFailure.class);
        assertThatThrownBy(() -> service.bootstrap("Bearer valid", "UTC", List.of(consent,consent))).isInstanceOf(ApplicationFailure.class);
        verifyNoInteractions(repository);
        var account = new Account(UUID.randomUUID(),null,null);
        when(repository.findByUid("uid")).thenReturn(new AccountRepository.Stored(account,false));
        assertThatThrownBy(() -> service.updateName("Bearer valid", "x".repeat(101))).isInstanceOf(ApplicationFailure.class);
        verify(repository, never()).updateName(any(), any());
        service.updateName("Bearer valid",null);
        verify(repository).updateName(account.id(),null);
    }
    @Test void providerDependencyFailuresAreNeverConvertedToGuest() {
        when(verifier.verify("valid")).thenThrow(new ApplicationFailure(ApplicationFailure.Kind.UNAVAILABLE,"SERVICE_UNAVAILABLE","Unavailable"));
        assertThatThrownBy(() -> service.optionalAccount("Bearer valid")).isInstanceOf(ApplicationFailure.class)
            .extracting(e -> ((ApplicationFailure)e).kind()).isEqualTo(ApplicationFailure.Kind.UNAVAILABLE);
        verifyNoInteractions(repository);
    }
}
