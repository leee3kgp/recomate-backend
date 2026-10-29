package com.recomate.user;

import com.recomate.user.infrastructure.DeletionRetryCommand;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeletionRetryCommandTests {
    @Test
    void recoveryCannotInitiateWithdrawalForActiveOrMissingUser() {
        var context = mock(ConfigurableApplicationContext.class);
        var repository = mock(UserRepository.class);
        var service = mock(UserService.class);
        String sub = UUID.randomUUID().toString();
        when(context.getBean(UserRepository.class)).thenReturn(repository);
        when(repository.find(sub)).thenReturn(Optional.empty(), Optional.of(
                new UserRecord(sub, "email@example.test", "name", List.of(), UserRecord.Status.ACTIVE, null)));
        var command = new DeletionRetryCommand(service, sub, context);
        assertThatThrownBy(() -> command.run(new DefaultApplicationArguments())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> command.run(new DefaultApplicationArguments())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(service);
    }

    @Test
    void recoveryResumesPersistedRequestAndClosesApplication() {
        var context = mock(ConfigurableApplicationContext.class);
        var repository = mock(UserRepository.class);
        var service = mock(UserService.class);
        String sub = UUID.randomUUID().toString();
        when(context.getBean(UserRepository.class)).thenReturn(repository);
        when(repository.find(sub)).thenReturn(Optional.of(new UserRecord(sub, null, null, List.of(), UserRecord.Status.DELETING, null)));
        new DeletionRetryCommand(service, sub, context).run(new DefaultApplicationArguments());
        verify(service).delete(sub);
        verify(context).close();
    }

    @Test
    void recoveryFailureStillClosesApplicationAndPropagatesFailure() {
        var context = mock(ConfigurableApplicationContext.class);
        var repository = mock(UserRepository.class);
        var service = mock(UserService.class);
        String sub = UUID.randomUUID().toString();
        when(context.getBean(UserRepository.class)).thenReturn(repository);
        when(repository.find(sub)).thenReturn(Optional.of(new UserRecord(sub, null, null, List.of(), UserRecord.Status.DELETING, null)));
        doThrow(new IllegalStateException("unavailable")).when(service).delete(sub);
        assertThatThrownBy(() -> new DeletionRetryCommand(service, sub, context).run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class);
        verify(context).close();
    }
}
