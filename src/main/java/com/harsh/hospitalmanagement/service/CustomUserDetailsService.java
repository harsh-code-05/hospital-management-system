package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.repository.UserRepository;
import com.harsh.hospitalmanagement.security.LocalAccount;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email)
            throws UsernameNotFoundException {

        User user = userRepository.findByEmail(email)
                .filter(candidate -> candidate.getGoogleId() == null && candidate.getPassword() != null)
                .orElseThrow(() ->
                        new UsernameNotFoundException("Invalid email or password"));

        return new LocalAccount.PasswordPrincipal(
                new LocalAccount(user.getId(), user.getEmail()), user.getPassword(), user.getRole());
    }
}
