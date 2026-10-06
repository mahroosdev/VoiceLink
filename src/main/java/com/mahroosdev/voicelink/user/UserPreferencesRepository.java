package com.mahroosdev.voicelink.user;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPreferencesRepository extends JpaRepository<UserPreferences, UUID> {}
