package com.autoinvoice.auth;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAuthTokenRepository extends JpaRepository<UserAuthToken, String> {
}
