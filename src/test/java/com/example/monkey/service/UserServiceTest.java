package com.example.monkey.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.entity.User;
import com.example.monkey.repository.AdminRepository;
import com.example.monkey.repository.UserRepository;
import com.example.monkey.security.PasswordPolicy;
import com.example.monkey.security.SessionIdentity;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AdminRepository adminRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private ImageCleanupService imageCleanupService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(
                userRepository, adminRepository, passwordEncoder, imageCleanupService, new PasswordPolicy());
    }

    @Test
    void registerRejectsWeakPasswordBeforeEncodingOrSaving() {
        String result = userService.register("alice", "Password1", "18888888888", null);

        assertThat(result).startsWith("error:password policy violation");
        verify(passwordEncoder, never()).encode(any());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void registerEncodesStrongPasswordAndUsesDefaultAvatar() {
        when(passwordEncoder.encode("StrongPass1!")).thenReturn("encoded-password");

        String result = userService.register("alice", "StrongPass1!", "18888888888", null);

        assertThat(result).isEqualTo("ok");
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User user = userCaptor.getValue();
        assertThat(user.getUsername()).isEqualTo("alice");
        assertThat(user.getPassword()).isEqualTo("encoded-password");
        assertThat(user.getAvatar()).isEqualTo("/images/default_avatar.png");
    }

    @Test
    void failedLoginDoesNotCreateSession() {
        User user = new User();
        user.setPassword("encoded-password");
        when(userRepository.findByUsername("alice")).thenReturn(user);
        when(passwordEncoder.matches("wrong-password", "encoded-password")).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest();

        String result = userService.login("alice", "wrong-password", request);

        assertThat(result).isEqualTo("username or password is incorrect");
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void successfulLoginCreatesSessionIdentityAfterPasswordMatch() {
        User user = new User();
        user.setId(7L);
        user.setPassword("encoded-password");
        when(userRepository.findByUsername("alice")).thenReturn(user);
        when(passwordEncoder.matches("StrongPass1!", "encoded-password")).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest();

        String result = userService.login("alice", "StrongPass1!", request);

        assertThat(result).isEqualTo("ok:USER");
        assertThat(request.getSession(false)).isNotNull();
        assertThat(request.getSession(false).getAttribute(SessionIdentity.USER_ID_ATTRIBUTE)).isEqualTo(7L);
        assertThat(request.getSession(false).getAttribute(SessionIdentity.IDENTITY_ATTRIBUTE)).isEqualTo("USER");
    }

    @Test
    void updatePasswordRejectsWeakPasswordBeforeSaving() {
        User user = new User();
        user.setPhone("18888888888");
        user.setPassword("old-password");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        String result = userService.updatePassword(7L, "18888888888", "Password1");

        assertThat(result).startsWith("error:password policy violation");
        assertThat(user.getPassword()).isEqualTo("old-password");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void updatePasswordEncodesStrongPasswordAfterPhoneMatch() {
        User user = new User();
        user.setPhone("18888888888");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("StrongPass1!")).thenReturn("encoded-password");

        String result = userService.updatePassword(7L, "18888888888", "StrongPass1!");

        assertThat(result).isEqualTo("ok");
        assertThat(user.getPassword()).isEqualTo("encoded-password");
        verify(userRepository).save(user);
    }
}
